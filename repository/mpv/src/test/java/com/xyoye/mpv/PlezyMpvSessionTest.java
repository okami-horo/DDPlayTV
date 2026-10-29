package com.xyoye.mpv;

import com.edde746.mpv.EndFileReason;
import com.edde746.mpv.LogLevel;
import com.edde746.mpv.MpvError;
import com.edde746.mpv.MpvEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PlezyMpvSessionTest {
    @Test
    public void endFileKeepsSourceReasonAndDecoderError() {
        List<String> events = new ArrayList<>();
        PlezyMpvSession.forwardEvent(listener(events), new MpvEvent.EndFile(EndFileReason.Error, 42L, MpvError.VoInitFailed, "decoder failed"));
        PlezyMpvSession.forwardEvent(listener(events), new MpvEvent.EndFile(EndFileReason.Stop, 41L, null, null));
        PlezyMpvSession.forwardEvent(listener(events), new MpvEvent.EndFile(EndFileReason.Eof, 42L, null, null));
        assertEquals("7:42:-15:4:decoder failed", events.get(0));
        assertEquals("7:41:0:2:null", events.get(1));
        assertEquals("7:42:0:0:null", events.get(2));
    }

    @Test
    public void lifecycleAndLogEventsPreserveOrderAndIdentity() {
        List<String> events = new ArrayList<>();
        PlezyMpvSession.Listener listener = listener(events);
        PlezyMpvSession.forwardEvent(listener, new MpvEvent.FileLoaded(9L));
        PlezyMpvSession.forwardEvent(listener, new MpvEvent.LogMessage("vd", LogLevel.Warn, "slow frame"));
        PlezyMpvSession.forwardEvent(listener, new MpvEvent.PlaybackRestart(9L, 1.25));
        assertEquals("8:9:0:0:null", events.get(0));
        assertEquals("2:null:3:0:vd: slow frame", events.get(1));
        assertEquals("21:9:0:0:null", events.get(2));
    }

    private PlezyMpvSession.Listener listener(List<String> events) {
        return new PlezyMpvSession.Listener() {
            public void onEvent(int type, Long source, int code, int reason, String message) {
                events.add(type + ":" + source + ":" + code + ":" + reason + ":" + message);
            }
            public void onProperty(String name, Long source, Object value) { }
        };
    }
}
