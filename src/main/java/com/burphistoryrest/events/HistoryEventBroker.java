/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.events;

import com.burphistoryrest.server.ApiException;
import com.burphistoryrest.telemetry.MetricsRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Arrival-ordered event ring. A monotonic sequence avoids loss when HTTP responses finish out of history-ID order. */
public final class HistoryEventBroker {
    private final String instanceId;
    private final int capacity;
    private final MetricsRegistry metrics;
    private final AtomicLong nextSequence = new AtomicLong();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    public HistoryEventBroker(String instanceId,int capacity,MetricsRegistry metrics){this.instanceId=instanceId;this.capacity=capacity;this.metrics=metrics;}

    public void publish(int historyId,Map<String,Object> summary){
        if(historyId<0||summary==null)return; long sequence=nextSequence.incrementAndGet();
        lock.lock();try{long dropped=0;while(events.size()>=capacity){events.removeFirst();dropped++;}if(dropped>0)metrics.eventDropped(dropped);
            events.addLast(new Event(sequence,historyId,Collections.unmodifiableMap(new LinkedHashMap<>(summary))));metrics.eventPublished();changed.signalAll();}finally{lock.unlock();}
    }

    public Batch awaitAfter(long afterSequence,int limit,long waitMillis){
        lock.lock();try{rejectExpired(afterSequence);List<Event> result=collect(afterSequence,limit);
            if(result.isEmpty()&&waitMillis>0){try{changed.awaitNanos(waitMillis*1_000_000L);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                rejectExpired(afterSequence);result=collect(afterSequence,limit);}
            long last=result.isEmpty()?afterSequence:result.getLast().sequence;
            return new Batch(result,last,EventCursorCodec.encode(instanceId,last),earliestSequenceUnsafe(),latestSequenceUnsafe());
        }finally{lock.unlock();}
    }

    /** Converts an initial history high-watermark to an event position. Future arrivals are then sequence-based. */
    public long positionAfterHistoryId(int historyId){
        lock.lock();try{for(Event event:events)if(event.historyId>historyId)return Math.max(0,event.sequence-1);return latestSequenceUnsafe();}finally{lock.unlock();}
    }

    public long earliestSequence(){lock.lock();try{return earliestSequenceUnsafe();}finally{lock.unlock();}}
    public long latestSequence(){lock.lock();try{return latestSequenceUnsafe();}finally{lock.unlock();}}
    public int size(){lock.lock();try{return events.size();}finally{lock.unlock();}}

    private List<Event> collect(long after,int limit){List<Event> result=new ArrayList<>();for(Event event:events)if(event.sequence>after){result.add(event);if(result.size()>=limit)break;}return List.copyOf(result);}
    private void rejectExpired(long after){long earliest=earliestSequenceUnsafe();if(!events.isEmpty()&&after>0&&after<earliest-1)throw ApiException.conflict("event_cursor_expired",
            "The requested event position is no longer in the event buffer",Map.of("afterSequence",after,"earliestAvailableSequence",earliest,"latestAvailableSequence",latestSequenceUnsafe()));}
    private long earliestSequenceUnsafe(){return events.isEmpty()?nextSequence.get():events.getFirst().sequence;}
    private long latestSequenceUnsafe(){return events.isEmpty()?nextSequence.get():events.getLast().sequence;}

    public record Event(long sequence,int historyId,Map<String,Object> item){}
    public record Batch(List<Event> events,long lastSequence,String cursor,long earliestSequence,long latestSequence){}
}
