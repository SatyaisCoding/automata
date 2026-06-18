package com.automata.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LogStreamService {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter createEmitter() {
        // 3 minute timeout
        SseEmitter emitter = new SseEmitter(180000L);
        this.emitters.add(emitter);
        
        emitter.onCompletion(() -> this.emitters.remove(emitter));
        emitter.onTimeout(() -> this.emitters.remove(emitter));
        emitter.onError((e) -> this.emitters.remove(emitter));
        
        try {
            emitter.send(SseEmitter.event().name("connect").data("Connected to Automata Log Stream"));
        } catch (Exception e) {
            this.emitters.remove(emitter);
        }
        
        return emitter;
    }

    public void broadcast(String message) {
        log.info(message);
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("log").data(message));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        this.emitters.removeAll(deadEmitters);
    }
}
