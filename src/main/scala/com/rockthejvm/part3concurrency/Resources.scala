package com.rockthejvm.part3concurrency

import cats.effect.IOApp
import cats.effect.IO
import com.rockthejvm.utils.debug
import scala.concurrent.duration._
import java.util.Scanner
import java.io.FileReader
import java.io.File
import cats.effect.kernel.Resource

object Resources extends IOApp.Simple {

  // use-case: manage a connection lifecycle
  class Connection(url: String) {
    def open(): IO[String]  = IO(s"opening connection to $url").debug
    def close(): IO[String] = IO(s"closing connection to $url").debug
  }

  val asyncFetchUrl =
    for {
      fib <- (new Connection("rockthejvm.com").open() *> IO.sleep(
        Int.MaxValue.seconds
      )).start
      _ <- IO.sleep(1.seconds) *> fib.cancel
    } yield ()
    // problem: leaking resources

  val correctAsyncFetchUrl =
    for {
      conn <- IO(new Connection("rockthejvm.com"))
      fib <- (conn.open() *> IO.sleep(Int.MaxValue.seconds))
        .onCancel(conn.close().void)
        .start
      _ <- IO.sleep(1.seconds) *> fib.cancel
    } yield ()

  // bracket pattern: someIO.bracket(useResourceCb)(releaseResourceCb)
  // bracket is equivalent to try-catches (pure FP)
  val bracketFetchUrl = IO(new Connection("rockthejvm.com"))
    .bracket(conn => conn.open() *> IO.sleep(Int.MaxValue.seconds))(conn =>
      conn.close().void
    )

  /** Exercise: read the file with the bracket pattern
    *   - open a scanner
    *   - read the file line by line, every 100 millis
    *   - close the scanner
    *   - if canelled/throws error, close the scanner
    */
  def openFileScanner(path: String): IO[Scanner] =
    IO(new Scanner(new FileReader(new File(path))))

  def bracketReadFile(path: String): IO[Unit] =
    for {
      _ <- IO.println(s"opening file at $path")
      scanner <- openFileScanner(path).bracket { scanner =>
        (IO(scanner.nextLine()).debug <* IO.sleep(100.millis))
          .iterateWhile(_ => scanner.hasNext())
      } { scanner =>
        IO.println(s"closing file at $path") *> IO(scanner.close())
      }
    } yield ()

  /** Resources
    */
  def connFromConfig(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket { scanner =>
        // acquire a connection based on the file
        IO(new Connection(scanner.nextLine())).bracket { conn =>
          conn.`open`().debug >> IO.never
        }(conn => conn.close().debug.void)
      }(scanner =>
        IO("closing file").debug >> IO(scanner.close())
      ) // nesting resources are tedious

  val connectionResource =
    Resource.make(IO(new Connection("rockthejvm.com")))(conn =>
      conn.close().void
    )

  val resourceFetchUrl = for {
    fib <- connectionResource.use(conn => conn.`open`() >> IO.never).start
    _   <- IO.sleep(1.second) >> fib.cancel
  } yield ()

  // resources are equivalent to brackets
  val simpleResource = IO("some resource")
  val usingResource: String => IO[String] = string =>
    IO(s"using the string $string").debug
  val releaseResource: String => IO[Unit] = string =>
    IO(s"finalizing the string: $string").debug.void

  val usingResourceWithBracket =
    simpleResource.bracket(usingResource)(releaseResource)
  val usingResourceWithResource =
    Resource.make(simpleResource)(releaseResource).use(usingResource)

  /** Exercise: read a text file with one line every 100 millis, using Resource
    */
  def getResourceFromFile(path: String) =
    Resource.make(openFileScanner(path))(scanner => IO(scanner.close()))

  def resourceExercise(path: String) =
    getResourceFromFile(path)
      .use { scanner =>
        (IO(scanner.nextLine()).debug <* IO.sleep(100.millis))
          .iterateWhile(_ => scanner.hasNext)
      }

  override def run: IO[Unit] =
    resourceExercise("src/main/scala/com/rockthejvm/part3concurrency/Resources.scala").void
}
