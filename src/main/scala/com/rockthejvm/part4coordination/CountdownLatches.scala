package com.rockthejvm.part4coordination

import cats.syntax.parallel.*
import cats.syntax.traverse.*
import cats.effect.IOApp
import cats.effect.{IO, Resource}
import cats.effect.std.CountDownLatch
import com.rockthejvm.utils.debug

import scala.concurrent.duration.*
import java.io.FileWriter
import java.io.File
import com.rockthejvm.part4coordination.CountdownLatches.FileServer.writeToFile
import scala.io.Source
import cats.effect.kernel.Resource
import scala.util.Random
import cats.effect.kernel.Deferred
import scala.collection.immutable.Queue
object CountdownLatches extends IOApp.Simple:

  // CD Latches are a coordination primitive initialized with a count.
  // All fibers calling await() on the CD Latch are (semantically) blocked.
  // When the internal count of the latch reaches 0 (via release() calls from other fibers),
  // all waiting fibers are unblocked

  def announcer(latch: CountDownLatch[IO]): IO[Unit] =
    for
      _ <- IO("Starting race shortly...").debug >> IO.sleep(2.seconds)
      _ <- IO("5...").debug >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("4...").debug >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("3...").debug >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("2...").debug >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("1...").debug >> IO.sleep(1.second)
      _ <- latch.release
      _ <- IO("GO GO GO!").debug
    yield ()

  def createRunner(id: Int, latch: CountDownLatch[IO]): IO[Unit] =
    for
      _ <- IO(s"[runner $id] waiting for signal...").debug
      _ <- latch.await
      _ <- IO(s"[runner $id] RUNNING!").debug
    yield ()

  val sprint: IO[Unit] =
    for
      latch        <- CountDownLatch[IO](5)
      announcerFib <- announcer(latch).start
      _ <- (1 to 10).toList.parTraverse(id => createRunner(id, latch))
      _ <- announcerFib.join
    yield ()

  /** Exercise: simulate a file downloader on multiple threads
    */
  object FileServer:
    val fileChunks = List(
      "I love Scala",
      "Cats Effect seems quite fun",
      "Never would I have thought I would do low-level concurrency WITH pure FP"
    )

    def getNumChunks: IO[Int] =
      IO(fileChunks.size)

    def getFileChunk(n: Int): IO[String] =
      IO(fileChunks(n))

    def writeToFile(path: String, contents: String): IO[Unit] =
      val fileResource =
        Resource.make(IO(new FileWriter(new File(path))))(writer =>
          IO(writer.close())
        )
      fileResource.use(writer => IO(writer.write(contents)))
    end writeToFile

    def appendFileContents(fromPath: String, toPath: String): IO[Unit] =
      val compositeResource =
        for
          reader <- Resource.make(IO(Source.fromFile(fromPath)))(source =>
            IO(source.close())
          )
          writer <- Resource.fromAutoCloseable(
            IO(new FileWriter(new File(toPath), true))
          )
        yield (reader, writer)

      compositeResource.use { case (reader, writer) =>
        IO(reader.getLines().foreach(writer.write))
      }

  end FileServer

  /**   - call file server API and get the number of chunks (n)
    *   - start a CD Latch
    *   - start n fibers which download a chunk of the file (use the file
    *     server's download chunk API)
    *   - block on the latch until each task has finished
    *   - after all chunks are done, stitch the files together under the same
    *     file on disk
    */
  def downloadFile(fileName: String, destFolder: String): IO[Unit] =
    def createFileDownloaderTask(
        id: Int,
        latch: CDLatch, // CountDownLatch[IO],
        filename: String,
        destFolder: String
    ): IO[Unit] =
      for
        _     <- IO(s"[task $id] downloading chunk...").debug
        _     <- IO.sleep((Random.nextDouble() * 1000).toInt.millis)
        chunk <- FileServer.getFileChunk(id)
        _     <- IO(s"[task $id] chunk downloaded: $chunk").debug
        _     <- IO(s"[task $id] writing to a file...").debug
        _     <- FileServer.writeToFile(s"$destFolder/$filename.part$id", chunk)
        _     <- latch.release
        _     <- IO(s"[task $id] Done").debug
      yield ()

    for
      n     <- FileServer.getNumChunks
      latch <- CDLatch.make(n) // CountDownLatch[IO](n)
      _     <- IO(s"Download started on $n fibers").debug
      _ <- (0 until n).toList.parTraverse(id =>
        createFileDownloaderTask(id, latch, fileName, destFolder)
      )
      _ <- latch.await
      _ <- (0 until n).toList.traverse(id =>
        FileServer.appendFileContents(
          s"$destFolder/$fileName.part$id",
          s"$destFolder/$fileName"
        )
      )
      _ <- IO("File downloaded").debug
    yield ()

  /** Exercise: implement your own CD Latch with Ref and Deferred
    */
  abstract class CDLatch:
    def release: IO[Unit]
    def await: IO[Unit]

  object CDLatch:
    type Signal = Deferred[IO, Unit]

    private case class State(signal: Signal, count: Int)

    def make(count: Int): IO[CDLatch] =
      for
        signal <- IO.deferred[Unit]
        ref    <- IO.ref(State(signal = signal, count = count))
      yield {
        new CDLatch:
          override def await: IO[Unit] =
            ref.get.flatMap {
              case State(_, 0) =>
                IO.unit
              case _ =>
                signal.get
            }

          override def release: IO[Unit] =
            ref.modify {
              case State(signal, 1) =>
                (State(signal, 0), signal.complete(()))
              case State(signal, count) =>
                (State(signal, if count > 1 then count - 1 else count), IO.unit)
            }
      }

  override def run: IO[Unit] =
    downloadFile("myScalafile.txt", "src/main/resources")
