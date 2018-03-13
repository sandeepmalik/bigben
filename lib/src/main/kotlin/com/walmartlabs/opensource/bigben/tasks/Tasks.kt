package com.walmartlabs.opensource.bigben.tasks

import com.google.common.base.Throwables.getStackTraceAsString
import com.google.common.util.concurrent.AsyncCallable
import com.google.common.util.concurrent.Futures.immediateFuture
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors.listeningDecorator
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.HazelcastInstanceAware
import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.hazelcast.nio.serialization.IdentifiedDataSerializable
import com.walmartlabs.opensource.bigben.entities.*
import com.walmartlabs.opensource.bigben.entities.EventStatus.ERROR
import com.walmartlabs.opensource.bigben.entities.EventStatus.PROCESSED
import com.walmartlabs.opensource.bigben.extns.domainProvider
import com.walmartlabs.opensource.bigben.hz.HzObjectFactory
import com.walmartlabs.opensource.bigben.hz.HzObjectFactory.OBJECT_ID.BULK_EVENT_TASK
import com.walmartlabs.opensource.bigben.hz.HzObjectFactory.OBJECT_ID.SHUTDOWN_TASK
import com.walmartlabs.opensource.bigben.processors.EventProcessor
import com.walmartlabs.opensource.bigben.processors.ProcessorRegistry
import com.walmartlabs.opensource.core.*
import com.walmartlabs.opensource.core.hz.ClusterSingleton
import com.walmartlabs.opensource.core.utils.Props
import java.lang.Runtime.getRuntime
import java.lang.System.currentTimeMillis
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Created by smalik3 on 2/23/18
 */
class BulkShardTask(private var shards: Collection<Pair<ZonedDateTime, Int>>? = null) : Callable<ShardStatusList>, IdentifiedDataSerializable, HazelcastInstanceAware, Idso(BULK_EVENT_TASK) {

    companion object {
        private val l = logger<BulkShardTask>()
        private val NO_OP = immediateFuture<List<ShardStatus>>(ArrayList())
        private val loader = createProvider<EventLoader<Pair<ZonedDateTime?, String>>>()
    }

    private lateinit var hz: HazelcastInstance

    override fun call(): ShardStatusList {
        try {
            return ShardStatusList(execute().get())
        } catch (e: Exception) {
            l.error("error in processing events", e.rootCause())
            throw RuntimeException(e)
        }
    }

    private fun execute(): ListenableFuture<List<ShardStatus>> {
        val shards = shards!!
        if (shards.isEmpty()) return NO_OP
        if (l.isDebugEnabled) l.debug("{}, executing bulk event task for buckets/shards on node: {}", shards.map { "${it.first}/${it.second}" }, hz.cluster.localMember.socketAddress)
        val fetchSizeHint = Props.int("max.events.in.memory", 100_000) / shards.size
        return shards.map { s ->
            try {
                ShardTask(s, fetchSizeHint, ProcessorRegistry.instance, loader).call().done(
                        { l.error("error in executing shard: bucket: {}, shard: {}", s.first, s.second, it.rootCause()) }) {
                    if (l.isInfoEnabled) l.info("shard processed, bucket: {}, shard: {}", s.first, s.second)
                }.catching {
                    l.error("error in executing shard, returning an ERROR status bucket: {}, shard: {}", s.first, s.second, it.rootCause())
                    ShardStatus(s.first, s.second, ERROR)
                }
            } catch (e: Exception) {
                l.error("error in submitting shard for execution: bucket: {}, shard: {}", s.first, s.second, e.rootCause())
                immediateFuture(ShardStatus(s.first, s.second, ERROR))
            }
        }.reduce()
    }

    override fun writeData(out: ObjectDataOutput) {
        out.writeInt(shards!!.size)
        shards!!.forEach {
            out.writeLong(it.first.toInstant().toEpochMilli())
            out.writeInt(it.second)
        }
    }

    override fun readData(`in`: ObjectDataInput) {
        shards = (1..`in`.readInt()).map { ZonedDateTime.ofInstant(Instant.ofEpochMilli(`in`.readLong()), UTC) to `in`.readInt() }.toList()
    }

    override fun setHazelcastInstance(hazelcastInstance: HazelcastInstance) {
        this.hz = hazelcastInstance
    }
}

class ShardTask<T>(private val p: Pair<ZonedDateTime, Int>, fetchSizeHint: Int,
                   private val processor: EventProcessor<Event>, private val loader: EventLoader<T>) : Callable<ListenableFuture<ShardStatus>> {

    companion object {
        private val l = logger<ShardTask<Any>>()

        private val index = AtomicInteger()
        private val scheduler = listeningDecorator(ScheduledThreadPoolExecutor(
                Props.int("evt.scheduler.worker.threads", 2 * getRuntime().availableProcessors()),
                ThreadFactory { Thread(it, "evt-processor#" + index.getAndIncrement()) }, CallerRunsPolicy()))
    }

    private val executionKey = "${p.first}[${p.second}]"
    private val fetchSize = max(10, min(fetchSizeHint, 400))

    override fun call(): ListenableFuture<ShardStatus> {
        if (l.isDebugEnabled) l.debug("{}, processing shard with fetch size: {}", executionKey, fetchSize)
        return loader.load(null, p.first, p.second, fetchSize).transformAsync { loaded ->
            if (loaded!!.second == null || loaded.second!!.isEmpty()) immediateFuture(loaded.first to loaded.second)
            else loaded.second!!.map { e ->
                schedule(e).done({
                    l.error("{}/{}/{} event has error in processing", executionKey, e.eventTime, e.id, it.rootCause())
                }) { if (l.isDebugEnabled) l.debug("{}/{}/{} event is processed successfully", executionKey, e.eventTime, e.id) }
            }.reduce().transformAsync { loader.load(loaded.first, p.first, p.second, fetchSize) }
        }.transform {
            it!!.second?.fold(false, { b, e -> b || e.status == ERROR })?.run {
                if (l.isDebugEnabled) {
                    if (this) l.debug("{}, errors in processing shard", executionKey)
                    else l.debug("{}, shard processed successfully", executionKey)
                }
                ShardStatus(p.first, p.second, if (this) ERROR else PROCESSED)
            } ?: ShardStatus(p.first, p.second, PROCESSED)
        }
    }

    private fun schedule(e: Event): ListenableFuture<Event> {
        val delay = e.eventTime!!.toInstant().toEpochMilli() - currentTimeMillis()
        return if (delay <= 0) {
            if (l.isDebugEnabled) l.debug("{}, event {} time has expired, processing immediately", executionKey, e.id)
            process(e).transformAsync { save(e) }
        } else {
            if (l.isDebugEnabled) l.debug("{}, scheduling event '{}' after delay {}, at {}", executionKey, e.id, delay, e.eventTime!!)
            AsyncCallable { processor(e) }.scheduleAsync(delay, MILLISECONDS, scheduler).transformAsync { save(it!!) }
        }
    }

    private fun process(e: Event): ListenableFuture<Event> {
        return try {
            if (l.isDebugEnabled) l.debug("{}, processing event: {}", executionKey, e.id)
            processor.invoke(e).apply {
                transform { if (l.isDebugEnabled) l.debug("{}, processed event: {}", executionKey, e.id) }.catching { ex ->
                    l.error("{}, error in processing event, marking it {}", executionKey, ERROR, ex.rootCause())
                    e.status?.let { e.status = ERROR; e.error = getStackTraceAsString(ex!!) }
                }
            }
        } catch (t: Throwable) {
            l.error("{}, error in processing event: {}", executionKey, e.id, t.rootCause())
            e.status = ERROR
            e.error = getStackTraceAsString(t.rootCause()!!)
            immediateFuture(e)
        }
    }

    private fun save(e: Event): ListenableFuture<Event> {
        if (l.isDebugEnabled) l.debug("{}, saving event: {} to the DB, the status is '{}'", executionKey, e.id, e.status)
        return domainProvider<Event>().save(e)
    }
}

internal class ShutdownTask : IdsoCallable(SHUTDOWN_TASK), Callable<Boolean> {
    override fun call(): Boolean {
        TODO()
    }
}

internal class StatusTask(private var serviceName: String? = null) : Idso(HzObjectFactory.OBJECT_ID.CLUSTER_STATUS_TASK), Callable<String> {
    override fun call() = if (ClusterSingleton.ACTIVE_SERVICES.contains(serviceName)) "Master" else "Slave"
    override fun writeData(out: ObjectDataOutput) = out.writeUTF(serviceName)
    override fun readData(`in`: ObjectDataInput) = `in`.let { serviceName = it.readUTF() }
}
