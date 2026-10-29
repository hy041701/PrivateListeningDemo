import com.example.privatelistening.common.AudioPacket;
import com.example.privatelistening.common.ListeningTiming;
import java.util.*;

/** 对实际编译的 common 类运行，无需 Android 设备或额外测试依赖。 */
class CoreChecks {
    static int checks;
    static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
    static void rejects(Runnable action, String name) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) {
            checks++; return;
        }
        throw new AssertionError(name);
    }
    static AudioPacket.Packet packet(long seq) {
        return new AudioPacket.Packet(new UUID(123, 456), 1, seq, seq * 5000, 48000, 2, new byte[960]);
    }
    public static void main(String[] args) {
        var timing = ListeningTiming.INSTANCE;
        var sample = timing.sample(1000000, 1202000, 1202300, 1004300);
        check(sample != null && sample.getOffsetUs() == 200000 && sample.getRoundTripUs() == 4000, "clock offset with processing delay");
        sample = timing.sample(1000000, 802000, 802300, 1004300);
        check(sample != null && sample.getOffsetUs() == -200000, "negative clock offset");
        check(timing.sample(1000, 2000, 1900, 3000) == null, "invalid remote time order");
        check(timing.sample(1000, 2000, 2100, 900) == null, "invalid local time order");
        check(timing.sample(0, 0, 0, 600000) == null, "excessive round trip");
        check(timing.speed(10000) == 1f, "drift dead band");
        check(timing.speed(50000) == 1.02f && timing.speed(-50000) == .98f, "correction direction and bound");

        var window = new ListeningTiming.PacketWindow();
        check(window.offer(packet(1)), "accept out of order");
        check(window.poll(100, false) == null, "wait for missing first packet");
        check(!window.offer(packet(1)), "discard duplicate");
        window.offer(packet(0));
        check(window.poll(108, false).getSequence() == 0, "reorder first packet");
        check(window.poll(108, false).getSequence() == 1, "release reordered second packet");
        check(!window.offer(packet(0)), "discard late packet");
        window.offer(packet(3));
        check(window.poll(200, true) == null, "wait for playback gap");
        check(window.poll(259, true) == null, "respect reorder window");
        check(window.poll(260, true).getSequence() == 3, "skip loss after deadline");
        window = new ListeningTiming.PacketWindow();
        window.offer(packet(1)); window.poll(0, false);
        var startupWindow = window;
        rejects(() -> startupWindow.poll(60, false), "startup must reject a gap");
        rejects(() -> startupWindow.offer(packet(999)), "reject oversized sequence jump");

        var protocol = AudioPacket.INSTANCE;
        var random = new Random(42);
        for (int i = 0; i < 500; i++) {
            int channels = i % 2 + 1, frameBytes = channels * 2;
            byte[] pcm = new byte[(1 + random.nextInt(1148 / frameBytes)) * frameBytes];
            random.nextBytes(pcm);
            var input = new AudioPacket.Packet(new UUID(random.nextLong(), random.nextLong()), i, i * 2L, 1000000L + i, i % 2 == 0 ? 44100 : 192000, channels, pcm);
            byte[] bytes = protocol.encode(input);
            byte[] offsetBytes = new byte[bytes.length + 7];
            System.arraycopy(bytes, 0, offsetBytes, 3, bytes.length);
            var decoded = protocol.decode(offsetBytes, 3, bytes.length);
            check(decoded != null && decoded.getStreamId().equals(input.getStreamId()) &&
                decoded.getSequence() == input.getSequence() && decoded.getPtsUs() == input.getPtsUs() &&
                decoded.getSampleRate() == input.getSampleRate() && Arrays.equals(decoded.getPcm(), pcm), "PCM wire round trip " + i);
            check(protocol.decode(bytes, 0, bytes.length - 1) == null, "reject truncated packet " + i);
        }
        check(protocol.decode(new byte[1201], 0, 1201) == null, "oversize datagram");
        check(protocol.decode(new byte[100], Integer.MAX_VALUE, 60) == null, "invalid offset");
        var corrupt = protocol.encode(packet(0)); corrupt[0] = 0;
        check(protocol.decode(corrupt, 0, corrupt.length) == null, "invalid magic");
        System.out.println("PASS: " + checks + " protocol, clock and reorder checks");
    }
}
