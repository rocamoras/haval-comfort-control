package br.com.redesurftank.havalcomfortcontrol.utils;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.com.redesurftank.havalcomfortcontrol.App;

/**
 * Log rotativo em disco, para que o diagnóstico sobreviva à morte do processo.
 *
 * Por que existe: o histórico de ações vive em memória (ClimateStateHolder) e o
 * logcat do device se mostrou inacessível nos uploads de diagnóstico — então um
 * reinício levava consigo toda a evidência de por que ele aconteceu. Aqui ficam
 * os eventos de ciclo de vida (início de processo, start/destroy do serviço, cada
 * motivo de restart) e as ações do controle automático, com data completa.
 *
 * Fica no storage device-protected: o serviço é directBootAware e precisa poder
 * escrever antes do unlock. Escrita numa thread própria — nunca bloqueia quem chama.
 */
public final class PersistentLog {

    private static final String TAG = "PersistentLog";

    private static final String DIR_NAME  = "diag";
    private static final String FILE_NAME = "diag.log";
    /** Rotaciona em ~192 KB e guarda um arquivo anterior → ~384 KB no total. */
    private static final long   MAX_BYTES = 192 * 1024L;
    /** Teto do que o upload de diagnóstico carrega (o mais recente). */
    public  static final int    DUMP_MAX_CHARS = 120_000;

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "persistent-log");
        t.setDaemon(true);
        return t;
    });

    /** Identifica o processo nas linhas, para separar uma encarnação da outra. */
    private static final String PROC = "pid" + Process.myPid();

    private PersistentLog() {}

    /**
     * Marca o início de um processo. Chamar de App.onCreate — a linha resultante é
     * o que denuncia um reinício silencioso: duas dessas seguidas sem nada entre
     * elas significa que o processo morreu e voltou.
     */
    public static void logProcessStart(String versionName) {
        write("PROC", "===== processo iniciado — versao " + versionName
                + " — uptime do device " + (SystemClock.elapsedRealtime() / 1000) + "s");
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        write(tag, message);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
        write(tag, message);
    }

    /** Só disco, sem duplicar no logcat (para o que já foi logado pelo chamador). */
    public static void write(String tag, String message) {
        final String line = STAMP.format(new Date()) + "  [" + PROC + "] " + tag + ": " + message;
        try {
            WRITER.execute(() -> appendLine(line));
        } catch (Exception e) {
            // Executor recusou (shutdown) — nada a fazer, o logcat já recebeu.
        }
    }

    /**
     * Conteúdo do log, do mais antigo para o mais novo, limitado aos últimos
     * {@code maxChars} caracteres. Nunca lança.
     */
    public static String dump(int maxChars) {
        try {
            File current  = file();
            File previous = new File(current.getPath() + ".1");
            StringBuilder sb = new StringBuilder();
            appendTail(previous, sb, maxChars);
            appendTail(current,  sb, maxChars);
            if (sb.length() > maxChars) {
                int cut = sb.length() - maxChars;
                // Descarta até a próxima quebra de linha para não começar no meio.
                int nl = sb.indexOf("\n", cut);
                sb.delete(0, nl >= 0 ? nl + 1 : cut);
                sb.insert(0, "(início truncado)\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "(falha lendo o log persistente: " + e.getMessage() + ")";
        }
    }

    public static void clear() {
        try {
            WRITER.execute(() -> {
                File current = file();
                //noinspection ResultOfMethodCallIgnored
                new File(current.getPath() + ".1").delete();
                //noinspection ResultOfMethodCallIgnored
                current.delete();
            });
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────

    private static File file() {
        Context ctx;
        try {
            ctx = App.getDeviceProtectedContext();
        } catch (Exception e) {
            ctx = App.getContext();
        }
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    /** Roda apenas na thread do WRITER. */
    private static void appendLine(String line) {
        try {
            File f = file();
            if (f.length() > MAX_BYTES) {
                File prev = new File(f.getPath() + ".1");
                //noinspection ResultOfMethodCallIgnored
                prev.delete();
                if (!f.renameTo(prev)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();   // sem rotação possível: melhor perder o antigo que crescer sem fim
                }
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
                w.write(line);
                w.write('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "falha escrevendo no log persistente: " + e.getMessage());
        }
    }

    private static void appendTail(File f, StringBuilder sb, int maxChars) {
        if (f == null || !f.isFile() || f.length() == 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len  = raf.length();
            long from = Math.max(0, len - maxChars);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            sb.append(new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        } catch (Exception e) {
            sb.append("(falha lendo ").append(f.getName()).append(": ").append(e.getMessage()).append(")\n");
        }
    }
}
