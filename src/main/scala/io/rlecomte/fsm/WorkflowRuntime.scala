package io.rlecomte.fsm

import cats.effect.IO
import cats.arrow.FunctionK
import cats.implicits._
import cats.effect.concurrent.Ref
import cats.effect.ContextShift

object WorkflowRuntime {
  import Workflow._
  type RollbackRef = Ref[IO, IO[Unit]]

  private class Run(store: WorkflowStore, rollback: RollbackRef) {

    private def tellIO(
        tracer: WorkflowTracer,
        step: Step[_]
    ): IO[Unit] = {

      val rollbackStep = for {
        _ <- tracer.logStepCompensationStarted(step)
        either <- step.compensate.attempt
        _ <- either match {
          case Right(_)  => tracer.logStepCompensationCompleted(step)
          case Left(err) => tracer.logStepCompensationFailed(step, err)
        }
      } yield ()

      rollback.modify(steps => (rollbackStep *> steps, ()))
    }

    private val runCompensation: IO[Unit] = rollback.get.flatten

    private def foldIO(
        tracer: WorkflowTracer
    )(implicit cs: ContextShift[IO]): FunctionK[WorkflowOp, IO] =
      new FunctionK[WorkflowOp, IO] {
        override def apply[A](op: WorkflowOp[A]): IO[A] = op match {
          case step @ Step(_, _, _, _) => processStep(tracer, step)
          case FromSeq(seq)            => seq.foldMap(foldIO(tracer))
          case FromPar(par) => {
            val parIO = par
              .foldMap(foldIO(tracer).andThen(IO.ioParallel.parallel))
            IO.ioParallel.sequential(parIO)
          }
        }
      }

    private def processStep[A](tracer: WorkflowTracer, step: Step[A]): IO[A] = {
      tracer.logStepStarted(step) *> step.effect.attempt.flatMap {
        case Right(a) =>
          tracer.logStepCompleted(step, a) *> tellIO(tracer, step).as(a)
        case Left(err) =>
          val retryIO = step.retryStrategy match {
            case NoRetry | LinearRetry(0) =>
              runCompensation *> IO
                .raiseError(
                  err
                )
            case LinearRetry(nb) =>
              processStep(
                tracer,
                step.copy(retryStrategy = LinearRetry(nb - 1))
              )
          }

          tracer.logStepFailed(step, err) *> retryIO
      }
    }

    def toIO[I, O](
        fsm: FSM[I, O]
    )(implicit cs: ContextShift[IO]): I => IO[O] = input => {
      store.logWorkflowExecution(
        fsm.name,
        tracer => fsm.f(input).foldMap(foldIO(tracer))
      )
    }
  }

  def run[I, O](
      store: WorkflowStore
  )(workflow: FSM[I, O])(implicit cs: ContextShift[IO]): I => IO[O] = input => {
    for {
      ref <- Ref.of[IO, IO[Unit]](IO.unit)
      result <- new Run(store, ref).toIO(workflow).apply(input)
    } yield result
  }
}
