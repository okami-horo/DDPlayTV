package com.xyoye.mpv;

import android.content.Context;
import android.view.Surface;
import com.edde746.mpv.MpvEvent;
import com.edde746.mpv.MpvError;
import com.edde746.mpv.MpvPlayer;
import com.edde746.mpv.PropertyChange;
import com.edde746.mpv.PropertyFormat;
import java.util.Map;
import kotlin.Unit;

/** Java ABI boundary; upstream Kotlin types must not appear in the public API. */
public final class PlezyMpvSession implements AutoCloseable {
    public static final int VIDEO_OUTPUT_ERROR = MpvError.VoInitFailed.getCode();
    public interface Listener {
        void onEvent(int type, Long sourceId, int code, int reason, String message);
        void onProperty(String name, Long sourceId, Object value);
    }

    private final MpvPlayer player;

    public PlezyMpvSession(Context context, Map<String, String> options, Listener listener) {
        player = MpvPlayer.Companion.create(context, new MpvPlayer.Listener() {
            @Override
            public void onEvent(MpvEvent event) {
                forwardEvent(listener, event);
            }

            @Override
            public void onPropertyChange(PropertyChange change) {
                Object value = null;
                if (change instanceof PropertyChange.Str) value = ((PropertyChange.Str) change).getValue();
                else if (change instanceof PropertyChange.Flag) value = ((PropertyChange.Flag) change).getValue();
                else if (change instanceof PropertyChange.Double) value = ((PropertyChange.Double) change).getValue();
                else if (change instanceof PropertyChange.Int64) value = ((PropertyChange.Int64) change).getValue();
                listener.onProperty(change.getName(), change.getSourceId(), value);
            }

            @Override
            public void onHook(MpvPlayer source, String name, long id) {
                source.continueHook(id);
            }
        }, 0L, config -> {
            for (Map.Entry<String, String> option : options.entrySet()) {
                config.setOption(option.getKey(), option.getValue());
            }
            return Unit.INSTANCE;
        });
    }

    static void forwardEvent(Listener listener, MpvEvent event) {
        Long source = event.getSourceId();
        if (event instanceof MpvEvent.FileLoaded) {
            listener.onEvent(8, source, 0, 0, null);
        } else if (event instanceof MpvEvent.PlaybackRestart) {
            listener.onEvent(21, source, 0, 0, null);
        } else if (event instanceof MpvEvent.EndFile) {
            MpvEvent.EndFile end = (MpvEvent.EndFile) event;
            listener.onEvent(7, source, end.getError() == null ? 0 : end.getError().getCode(),
                    end.getReason() == null ? -1 : end.getReason().getId(), end.getErrorMessage());
        } else if (event instanceof MpvEvent.LogMessage) {
            MpvEvent.LogMessage log = (MpvEvent.LogMessage) event;
            int level;
            switch (log.getLevel()) {
                case Fatal: level = 5; break;
                case Error: level = 4; break;
                case Warn: level = 3; break;
                default: level = 2;
            }
            listener.onEvent(2, null, level, 0, log.getPrefix() + ": " + log.getText());
        }
    }

    public Long command(String... arguments) { return player.command(arguments); }
    public void setLogLevel(String level) { player.setLogLevel(level); }
    public String getString(String name) { return player.getString(name); }
    public Integer getInt(String name) { return player.getInt(name); }
    public Double getDouble(String name) { return player.getDouble(name); }
    public Boolean getFlag(String name) { return player.getFlag(name); }
    public void setProperty(String name, String value) { player.setProperty(name, value); }
    public void setProperty(String name, double value) { player.setProperty(name, value); }
    public void setProperty(String name, boolean value) { player.setProperty(name, value); }
    public void observe(String name, String format) { player.observeProperty(name, PropertyFormat.valueOf(format)); }

    public void attachSurfaces(Surface video, Surface osd, long videoGeneration, long osdGeneration) {
        player.attachSurfaces(video, osd, videoGeneration, osdGeneration, null);
    }

    @Override
    public void close() { player.close(); }
}
