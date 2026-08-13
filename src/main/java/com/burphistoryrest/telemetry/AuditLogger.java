/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.telemetry;

import burp.api.montoya.logging.Logging;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.security.ApiPrincipal;
import com.burphistoryrest.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronous rotating JSONL access log. It never records tokens, query strings, or Burp traffic. */
public final class AuditLogger implements AutoCloseable {
    private static final int MEMORY_LIMIT = 10_000;
    private static final int WRITE_QUEUE_LIMIT = 8_192;
    private final boolean enabled;
    private final Path path;
    private final long maxBytes;
    private final int retainedFiles;
    private final Logging logging;
    private final MetricsRegistry metrics;
    private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(WRITE_QUEUE_LIMIT);
    private final AtomicBoolean running = new AtomicBoolean();
    private final Thread writer;

    public AuditLogger(ApiSettings settings, Logging logging, MetricsRegistry metrics) {
        this.enabled=settings.auditEnabled();this.path=Path.of(settings.auditLogPath()).toAbsolutePath().normalize();
        this.maxBytes=settings.auditMaxBytes();this.retainedFiles=settings.auditRetainedFiles();this.logging=logging;this.metrics=metrics;
        if(enabled){running.set(true);writer=new Thread(this::writeLoop,"burp-history-rest-audit");writer.setDaemon(true);writer.start();}
        else writer=null;
    }

    public void record(String requestId,String client,ApiPrincipal principal,String method,String pathOnly,
                       int status,long durationMs,long bytes,boolean raw){
        if(!enabled)return;
        Map<String,Object> event=new LinkedHashMap<>();event.put("timestamp",Instant.now().toString());event.put("requestId",requestId);
        event.put("client",client);event.put("principalId",principal==null?null:principal.id());event.put("principalLabel",principal==null?null:principal.label());
        event.put("method",method);event.put("path",pathOnly);event.put("status",status);event.put("durationMs",durationMs);
        event.put("responseBytes",bytes);event.put("rawDataReturned",raw);
        synchronized(recent){recent.addLast(Collections.unmodifiableMap(new LinkedHashMap<>(event)));while(recent.size()>MEMORY_LIMIT)recent.removeFirst();}
        if(enabled&&!queue.offer(Json.stringify(event))){metrics.auditFailure();logging.logToError("REST API audit queue is full; one metadata event was dropped");}
    }

    public boolean enabled(){return enabled;}

    public List<Map<String,Object>> recent(int limit){int safe=Math.max(1,Math.min(limit,2000));synchronized(recent){List<Map<String,Object>> all=new ArrayList<>(recent);int start=Math.max(0,all.size()-safe);return List.copyOf(all.subList(start,all.size()));}}

    private void writeLoop(){
        try{Files.createDirectories(path.getParent());while(running.get()||!queue.isEmpty()){
            String line=queue.poll(500, TimeUnit.MILLISECONDS);if(line==null)continue;append(line);
            List<String> batch=new ArrayList<>(255);queue.drainTo(batch,255);for(String next:batch)append(next);
        }}catch(InterruptedException e){Thread.currentThread().interrupt();drainBestEffort();}
        catch(IOException|RuntimeException e){metrics.auditFailure();logging.logToError("REST API audit writer stopped",e);}
    }

    private void append(String line)throws IOException{rotateIfNeeded(line.length()+1L);Files.writeString(path,line+"\n",StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,StandardOpenOption.APPEND,StandardOpenOption.WRITE);}
    private void rotateIfNeeded(long incoming)throws IOException{
        if(!Files.exists(path)||Files.size(path)+incoming<maxBytes)return;
        for(int i=retainedFiles-1;i>=1;i--){Path from=Path.of(path+"."+i),to=Path.of(path+"."+(i+1));if(Files.exists(from))Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
        Files.move(path,Path.of(path+".1"),StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(Path.of(path+"."+(retainedFiles+1)));
    }
    private void drainBestEffort(){String line;while((line=queue.poll())!=null)try{append(line);}catch(IOException e){metrics.auditFailure();break;}}
    @Override public void close(){running.set(false);if(writer!=null){writer.interrupt();try{writer.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}
