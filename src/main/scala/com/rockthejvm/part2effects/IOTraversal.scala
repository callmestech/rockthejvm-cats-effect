package com.rockthejvm.part2effects

import cats.effect.IOApp
import cats.effect.IO
import cats.Traverse
import cats.instances.list._
import scala.concurrent.Future
import scala.util.Random

object IOTraversal extends IOApp.Simple {

  import scala.concurrent.ExecutionContext.Implicits.global

  val workLoad: List[String] = List(
    "I quite like CE",
    "Scala is awesome",
    "looking forward to some awesome stuff"
  )

  def heavyComputation(string: String): Future[Int] = Future {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").size
  }

  def clunkyFutures() = {
    val futures: List[Future[Int]] = workLoad.map(heavyComputation)
    futures.foreach(_.foreach(println))
  }

  val listTraverse: Traverse[List] = Traverse[List]

  def traverseFutures() = {

    val singleFuture: Future[List[Int]] =
      listTraverse.traverse(workLoad)(heavyComputation)
    singleFuture.foreach(println)
  }

  // traverse for IO
  def computeAsIO(string: String) = IO {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").size
  }.debug

  val ios: List[IO[Int]]      = workLoad.map(computeAsIO)
  val singleIO: IO[List[Int]] = listTraverse.traverse(workLoad)(computeAsIO)

  // parallel traversal
  import cats.syntax.parallel._

  val parallelSingleIO: IO[List[Int]] = workLoad.parTraverse(computeAsIO)

  /** Exercises
    */
  import cats.syntax.traverse._

  def sequence[A](ios: List[IO[A]]): IO[List[A]] =
    ios.traverse(identity)

  // hard version
  def sequence_v2[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] =
    Traverse[F].traverse(ios)(identity)

  // parallel version
  def parSequence[A](ios: List[IO[A]]): IO[List[A]] =
    ios.parTraverse(identity)

  // hard version
  def parSequence_v2[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] =
    ios.parTraverse(identity)

  // existing sequence API
  val singleIO_v2 = listTraverse.sequence(ios)

  // parallel sequencing 
  val parallelSingleIO_v2 = parSequence(ios)
  val parallelSingleIO_v3 = ios.parSequence 

  override def run: IO[Unit] =
    parSequence_v2(ios).debug.void
}
