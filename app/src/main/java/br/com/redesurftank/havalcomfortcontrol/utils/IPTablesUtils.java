package br.com.redesurftank.havalcomfortcontrol.utils;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class IPTablesUtils {

    public static boolean unlockInputOutputAll() {
        return unlockOutput() && unlockInput();
    }

    private static boolean unlockOutput() {
        IShizukuService shizukuService = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        IRemoteProcess checkProc = null;
        IRemoteProcess insertProc = null;
        try {
            checkProc = shizukuService.newProcess(new String[]{"iptables", "-C", "OUTPUT", "-j", "ACCEPT"}, null, null);
            if (checkProc == null) throw new Exception("Failed to create process for OUTPUT check");
            checkProc.waitFor();
            int checkExit = checkProc.exitValue();
            closeStreams(checkProc);
            if (checkExit == 0) return true;

            insertProc = shizukuService.newProcess(new String[]{"iptables", "-I", "OUTPUT", "1", "-j", "ACCEPT"}, null, null);
            if (insertProc == null) throw new Exception("Failed to create process for OUTPUT insert");
            insertProc.waitFor();
            int insertExit = insertProc.exitValue();
            closeStreams(insertProc);
            return insertExit == 0;
        } catch (Exception ex) {
            Log.e("IPTablesUtils", "Exception unlocking OUTPUT chain", ex);
            return false;
        } finally {
            destroyQuietly(checkProc);
            destroyQuietly(insertProc);
        }
    }

    private static boolean unlockInput() {
        IShizukuService shizukuService = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        IRemoteProcess checkProc = null;
        IRemoteProcess insertProc = null;
        try {
            checkProc = shizukuService.newProcess(new String[]{"iptables", "-C", "INPUT", "-j", "ACCEPT"}, null, null);
            if (checkProc == null) throw new Exception("Failed to create process for INPUT check");
            checkProc.waitFor();
            int checkExit = checkProc.exitValue();
            closeStreams(checkProc);
            if (checkExit == 0) return true;

            insertProc = shizukuService.newProcess(new String[]{"iptables", "-I", "INPUT", "1", "-j", "ACCEPT"}, null, null);
            if (insertProc == null) throw new Exception("Failed to create process for INPUT insert");
            insertProc.waitFor();
            int insertExit = insertProc.exitValue();
            closeStreams(insertProc);
            return insertExit == 0;
        } catch (Exception ex) {
            Log.e("IPTablesUtils", "Exception unlocking INPUT chain", ex);
            return false;
        } finally {
            destroyQuietly(checkProc);
            destroyQuietly(insertProc);
        }
    }

    private static void closeStreams(IRemoteProcess proc) throws RemoteException {
        ParcelFileDescriptor pfd;
        pfd = proc.getInputStream();
        if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
        pfd = proc.getOutputStream();
        if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
        pfd = proc.getErrorStream();
        if (pfd != null) try { pfd.close(); } catch (IOException ignored) {}
    }

    private static void destroyQuietly(IRemoteProcess proc) {
        if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
    }
}
