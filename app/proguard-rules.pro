# Interfaces AIDL da ROM: os Stub/Proxy gerados dependem do DESCRIPTOR e da ordem
# das transacoes para casar com o servico do outro lado. Manter intactos.
-keep class com.beantechs.** { *; }
-keepnames class com.beantechs.**
-keepclassmembers class com.beantechs.** { *; }

# IConnectivityManager (@hide) usado para ligar/desligar a ancora de Wi-Fi.
-keep class android.net.IConnectivityManager { *; }
-keep class android.net.IConnectivityManager$* { *; }

# IShizukuService / IRemoteProcess vem do AAR do Shizuku, tambem por AIDL.
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }
