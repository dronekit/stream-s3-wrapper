package io.dronekit.cloud

import java.io.ByteArrayInputStream
import java.util

import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.typesafe.scalalogging.Logger

import scala.collection.JavaConversions._
import scala.collection._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * Created by Jason Martens <jason.martens@3drobotics.com> on 8/18/15.
  *
  */

/**
  * An Akka GraphStage which uploads a ByteString to S3 as a multipart upload.
  * @param s3Client The S3 client to use to connect to S3
  * @param bucket The name of the bucket to upload to
  * @param key The key to use for the upload
  * @param logger a logger for debug messages
  *
  *               This stage has a 10MB buffer for input data, as the buffer fills up, each chunk is uploaded to s3.
  *               Each uploaded chunk is kept track of in the chunkMap. When all the parts in the chunkMap are uploaded
  *               the stream finishes.
  */
class S3UploadFlow(s3Client: AmazonS3Client, bucket: String, key: String, logger: Logger)
                  (implicit ec: ExecutionContext) extends GraphStageWithMaterializedValue[FlowShape[ByteString, UploadPartResult], Future[CompleteMultipartUploadResult]] {
  require(ec != null, "Execution context was null!")

  val in: Inlet[ByteString] = Inlet("S3UploadFlow.in")
  val out: Outlet[UploadPartResult] = Outlet("S3UploadFlow.out")
  override val shape: FlowShape[ByteString, UploadPartResult] = FlowShape(in, out)

  /**
    * All state for the stage *must* be contained inside the createLogic method
    */
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[CompleteMultipartUploadResult]) = {
    val p: Promise[CompleteMultipartUploadResult] = Promise()
    (new GraphStageLogic(shape) {
      override def preStart(): Unit = pull(in)

      val retries = 2

      // number of retries for each s3 part
      sealed trait UploadState
      case object UploadStarted extends UploadState
      case class UploadFailed(ex: Throwable) extends UploadState
      case object UploadCompleted extends UploadState

      case class UploadChunk(number: Long, data: ByteString, state: UploadState = UploadStarted, etag: Option[PartETag])

      case class UploadResult(number: Long, state: UploadState, etag: Option[PartETag] = None)

      val MaxQueueSize = 10
      val MinUploadChunkSize = 1024 * 1024 * 10 // 10 MiB
      val chunkMap = new mutable.LongMap[UploadChunk](100)
      var buffer = ByteString.empty
      var partNumber = 1
      val uploadRequest = new InitiateMultipartUploadRequest(bucket, key)
      var uploadResults: List[UploadPartResult] = List()
      val multipartUpload = s3Client.initiateMultipartUpload(uploadRequest)
      val callback = getAsyncCallback(onAsyncInput)

      setHandler(in, new InHandler {
        /**
          * Buffer elements until we have the minimum size we would like to upload to S3
          */
        override def onPush(): Unit = {
          val elem = grab(in)
          buffer ++= elem

          if (buffer.length > MinUploadChunkSize) {
            uploadBuffer().onComplete(input => callback.invoke(input))
          }
          pushAndPull()
        }

        override def onUpstreamFinish(): Unit = {
          // Upload whatever is left in the buffer, since we will not be getting any more data
          uploadBuffer().onComplete(input => callback.invoke(input))
          pushAndPull()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          p.tryFailure(ex)
          failStage(ex)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pushAndPull()
        }
      })

      /**
        * Push any available data to the out port, and pull if the in port is available.
        * When all chunks are completed, also complete the upload and the stage.
        */
      private def pushAndPull(): Unit = {
        if (uploadResults.nonEmpty && !isClosed(out) && isAvailable(out)) {
          push(out, uploadResults.head)
          uploadResults = uploadResults.tail
        }
        if (!hasBeenPulled(in) && !isClosed(in) && outstandingChunks < MaxQueueSize) {
          pull(in)
        }

        if (outstandingChunks == 0 && isClosed(in)) {
          try {
            completeUpload()
            completeStage()
          } catch {
            case ex: Throwable =>
              logger.error(s"Upload failed to s3://$bucket/$key while trying to complete with $ex")
              abortUpload()
              failStage(ex)
          }
        }
      }


      /**
        * Callback for when each part is done uploading
        */
      private def onAsyncInput(input: Try[UploadPartResult]): Unit = input match {
        case Failure(ex) =>
          logger.error(s"Upload failed to s3://$bucket/$key with $ex")
          abortUpload()
          failStage(ex)
        case Success(result) =>
          uploadResults = uploadResults :+ result
          setPartCompleted(result.getPartNumber, Some(result.getPartETag))
          pushAndPull()
      }

      /**
        * Set a part in the chunkmap as complete
        * @param partNumber number of the chunk
        * @param etag etag of the chunk
        */
      private def setPartCompleted(partNumber: Long, etag: Option[PartETag]): Unit = {
        chunkMap(partNumber) = UploadChunk(partNumber, ByteString.empty, UploadCompleted, etag)
      }

      private def outstandingChunks: Long = {
        chunkMap.filter { case (part, chunk) => chunk.state != UploadCompleted }.toList.length
      }

      private def abortUpload() = {
        logger.debug(s"Aborting upload to s3://$bucket/$key with id ${multipartUpload.getUploadId}")
        s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, multipartUpload.getUploadId))
      }

      private def completeUpload() = {
        val etagList = chunkMap.toList.sortBy { case (part, chunk) => part }.map { case (part, chunk) => chunk.etag.get }
        val etagArrayList: util.ArrayList[PartETag] = new util.ArrayList[PartETag](etagList.toIndexedSeq)
        val completeRequest = new CompleteMultipartUploadRequest(bucket, key, multipartUpload.getUploadId, etagArrayList)
        val result = s3Client.completeMultipartUpload(completeRequest)
        logger.debug(s"Completed upload to s3://$bucket/$key (${etagArrayList.length} parts)")
        p.success(result)
      }

      /**
        * Update the state of the stage for a part that will be uploaded
        */
      private def uploadBuffer(): Future[UploadPartResult] = {
        chunkMap(partNumber) = UploadChunk(partNumber, buffer, UploadStarted, None)
        val uploadFuture = uploadPartToAmazon(buffer, partNumber, multipartUpload.getUploadId, bucket, key)
        partNumber += 1
        buffer = ByteString.empty
        uploadFuture
      }

      /**
        * Upload a buffer of data into s3, retries 2 times by default
        * @param buffer data to upload
        * @param partNumber the chunk for the chunkMap
        * @param multipartId id of the multipart upload
        * @param bucket s3 bucket name
        * @param key name of file
        * @return the etag of the part upload
        */
      private def uploadPartToAmazon(buffer: ByteString, partNumber: Int, multipartId: String, bucket: String,
                                     key: String): Future[UploadPartResult] = {

        def uploadHelper(retryNumLocal: Int): Future[UploadPartResult] = {
          if (retryNumLocal >= retries)
            Future.failed(new AWSException(s"Uploading part failed for part $partNumber and multipartId: $multipartId"))
          else {
            val uploadFuture = Future {
              val partUploadRequest = new UploadPartRequest()
                .withBucketName(bucket)
                .withKey(key)
                .withUploadId(multipartId)
                .withPartNumber(partNumber)
                .withPartSize(buffer.length)
                .withInputStream(new ByteArrayInputStream(buffer.toArray[Byte]))
              s3Client.uploadPart(partUploadRequest)
            }
            uploadFuture.recoverWith {
              case ex =>
                logger.error(s"Caught $ex, retry attempt $retryNumLocal with $ex")
                uploadHelper(retryNumLocal + 1)
            }
            uploadFuture
          }
        }

        uploadHelper(0)
      }

    }, p.future)
  }
}
