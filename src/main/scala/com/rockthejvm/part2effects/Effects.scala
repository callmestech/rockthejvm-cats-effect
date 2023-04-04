package com.rockthejvm.part2effects

object Effects {

  // pure functional programming
  // substitution

  def combine(a: Int, b: Int): Int = a + b
  val five                         = combine(2, 3)
  val five_v2                      = 2 + 3

  // referential transparency = can replace an expression with its value
  // as many times as we want without changing behavior

  // example : print to the console
  val printSomething: Unit    = println("Cats Effect")
  val printSomething_v2: Unit = ()

  // example : change a variable
  var anInt                = 0
  val changingVar: Unit    = (anInt += 1)
  val changingVar_v2: Unit = () // not the same

  // side effects are inevitable for useful programs

  // effect

  /*
    Effect types
    Properties:
    - type signature describes the kind of calculation that will be performed
    - type signature describes the VALUE that will be calculated
    - when side effects are needed, effect construction is separate from effect execution
   */

  /*
     example: Option is an effect type
     - describes a possibly absent value
     - computes a value of type A, if it's exists
     - side effects are not needed
   */
  val anOption: Option[Int] = Option(42)

  /*
    example: Future
    - describes an asynchronous computation
    - computes a value of type A, if it's successfull
    - side effect is required (allocating / scheduling a thread), execution is NOT separate from effect execution
   */
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future
  val aFuture: Future[Int] = Future(42)

  // example: MyIO data type from the Monads lesson
  case class MyIO[A](unsafeRun: () => A) {
    def map[B](f: A => B): MyIO[B] =
      MyIO(() => f(unsafeRun()))

    def flatMap[B](f: A => MyIO[B]): MyIO[B] =
      MyIO(() => f(unsafeRun()).unsafeRun())
  }

  val anIO: MyIO[Int] = MyIO(() => {
    println("I'm writing something...")
    42
  })

  /** Exercises
    *   1. An IO which returns the current time of the system 2. An IO which 2.
    *      measures the duration of a computation 3. An IO that prints something
    *      to the console 4. An IO which reads a line (a string) from the std
    *      input
    */

  // 1
  val currentTime: MyIO[Long] =
    MyIO(() => System.currentTimeMillis)

  // 2
  def measure[A](computation: MyIO[A]): MyIO[Long] =
    for
      start <- currentTime
      _     <- computation
      end   <- currentTime
    yield end - start

  def withMeasure[A](computation: MyIO[A]): MyIO[A] =
    for
      start <- currentTime
      a     <- computation
      end   <- currentTime
      _     <- consolePutStrLn(s"Computation took ${end - start} millis")
    yield a
  // 3
  def consolePutStrLn(str: String): MyIO[Unit] =
    MyIO(() => println(str))

  // 4
  import scala.io.StdIn

  val readLine: MyIO[String] =
    MyIO(() => StdIn.readLine())

  def main(args: Array[String]): Unit = {
    
    withMeasure(MyIO(() => Thread.sleep(2000))).unsafeRun()
  }
}
