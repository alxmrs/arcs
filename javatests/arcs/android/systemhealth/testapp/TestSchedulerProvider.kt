/*
 * Copyright 2020 Google LLC.
 *
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 *
 * Code distributed by Google as part of this project is also subject to an additional IP rights
 * grant found at
 * http://polymer.github.io/PATENTS.txt
 */

package arcs.android.systemhealth.testapp

import arcs.android.systemhealth.testapp.Executors as ArcsExecutors
import arcs.core.host.SchedulerProvider
import arcs.core.util.Scheduler
import arcs.core.util.TaggedLog
import arcs.jvm.util.JvmTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * A [JvmSchedulerProvider] variant supports evaluations on diverse threading
 * models by dependency injections of executors.
 */
class TestSchedulerProvider(
  private val baseCoroutineContext: CoroutineContext,
  private val maxThreadCount: Int =
    maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
  private val threadPriority: Int = DEFAULT_THREAD_PRIORITY
) : SchedulerProvider {
  private val log = TaggedLog { "TestSchedulerProvider" }
  private val providedSoFar = atomic(0)
  private val threads = arrayOfNulls<Thread>(maxThreadCount)
  private val dispatchers = mutableListOf<CoroutineDispatcher>()
  private val executors = mutableListOf<ExecutorService>()
  private val schedulersByArcId = ConcurrentHashMap<String, Scheduler>()

  @Synchronized
  override fun invoke(arcId: String): Scheduler {
    schedulersByArcId[arcId]?.let { return it }

    val dispatcher = if (dispatchers.size == maxThreadCount) {
      dispatchers[providedSoFar.getAndIncrement() % maxThreadCount]
    } else {
      val threadIndex = providedSoFar.getAndIncrement()
      (ArcsExecutors.schedulers?.next() ?: Executors
        .newSingleThreadExecutor {
          val thread = threads[threadIndex % maxThreadCount]
          if (thread != null && thread.isAlive) return@newSingleThreadExecutor thread

          if (thread?.isAlive == false) log.info {
            "Creating a new thread (index: ${threadIndex % maxThreadCount}) because " +
              "a previously-created one had died."
          }

          Thread(it).apply {
            priority = threadPriority
            name = "Scheduler-Thread#${threadIndex % maxThreadCount}"
            threads[threadIndex % maxThreadCount] = this
          }
        })
        .also { executors.add(it) }
        .asCoroutineDispatcher()
        .also { dispatchers.add(it) }
    }

    val schedulerParentJob = Job(baseCoroutineContext[Job])
    schedulerParentJob.invokeOnCompletion {
      schedulersByArcId.remove(arcId)
    }

    val schedulerContext = baseCoroutineContext +
      schedulerParentJob +
      CoroutineName("ArcId::$arcId") +
      dispatcher

    return Scheduler(schedulerContext, timer = JvmTime).also { schedulersByArcId[arcId] = it }
  }

  @Synchronized
  override fun cancelAll() {
    schedulersByArcId.forEach { (_, scheduler) -> scheduler.cancel() }
    if (ArcsExecutors.schedulers == null) {
      // Shutting down the executors this scheduler spun up.
      // Delegated executors are managed outside the scheduler.
      executors.forEach {
        it.shutdown()
        it.awaitTermination(1, TimeUnit.SECONDS)
      }
    }
    threads.forEach { it?.interrupt() }
  }

  companion object {
    private const val DEFAULT_THREAD_PRIORITY = Thread.NORM_PRIORITY + 1
  }
}
