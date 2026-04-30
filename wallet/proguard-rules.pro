# ============================================================
# OpenWallet NYC — ProGuard / R8 rules
# ============================================================

# --------------- General -----------------
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Do NOT disable obfuscation — obfuscation protects key-derivation logic from reverse engineering
# -dontobfuscate   <-- intentionally removed

# Keep line numbers in stack traces for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --------------- Android -----------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.preference.Preference
# Fragments are restored by class name from saved Bundle state — must not be renamed
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.fragment.app.DialogFragment
-keep public class * extends android.app.Fragment
-keep public class * extends android.app.DialogFragment
# Dialogs shown via FragmentManager tag are also restored by class name
-keep public class * extends androidx.appcompat.app.AlertDialog
-keep public class * extends android.app.Dialog
# AsyncTask subclasses referenced as anonymous/inner classes
-keep public class * extends android.os.AsyncTask
# Preference screens
-keep public class * extends androidx.preference.PreferenceFragmentCompat
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# --------------- ButterKnife 7 -----------
-keep class butterknife.** { *; }
-dontwarn butterknife.internal.**
-keep class **$$ViewBinder { *; }
-keepclasseswithmembernames class * {
    @butterknife.* <fields>;
}
-keepclasseswithmembernames class * {
    @butterknife.* <methods>;
}

# --------------- Protobuf ----------------
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --------------- Spongycastle / Bouncy Castle ----------------
-keep class org.spongycastle.** { *; }
-dontwarn org.spongycastle.**

# --------------- bitcoinj / openwallet-core ----------------
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**
-keep class com.openwallet.core.** { *; }
-keep class com.openwallet.stratumj.** { *; }

# Keep wallet serialization model (must survive obfuscation)
-keep class com.openwallet.core.protos.** { *; }

# --------------- Guava ------------------
-dontwarn sun.misc.Unsafe
-dontwarn com.google.common.collect.MinMaxPriorityQueue
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
# bitcoinj's Threading class uses CycleDetectingLockFactory (a Guava concurrent utility).
# R8 enum-unboxing optimisation breaks the Policies enum static initialiser → NPE at Wallet.<init>.
# Keep all Guava concurrent utilities to prevent this.
-keep class com.google.common.util.concurrent.** { *; }
# Also keep enums throughout Guava so R8 doesn't unbox them
-keepclassmembers enum com.google.common.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
}

# --------------- OkHttp -----------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.squareup.okhttp.**
-dontwarn javax.annotation.**

# --------------- Wallet UI tasks / adapters (referenced by name in reflection-safe paths) ------
# Keep all wallet-module classes that are NOT Activities/Fragments (those are covered above)
# Tasks, adapters and loaders are often inner or anonymous classes whose names are stored
# in Bundles, Intents, or reconstructed by the Android framework.
-keep class com.openwallet.wallet.tasks.** { *; }
-keep class com.openwallet.wallet.ui.** { *; }
-keep class com.openwallet.wallet.service.** { *; }
-keep class com.openwallet.wallet.util.** { *; }

# --------------- ACRA -------------------
-keep class org.acra.** { *; }
-dontwarn org.acra.**

# --------------- Misc -------------------
-dontwarn sun.misc.Cleaner
-dontwarn java.nio.file.**
-dontwarn sun.nio.ch.DirectBuffer
-dontwarn net.jcip.annotations.GuardedBy
-dontwarn com.subgraph.orchid.**
-dontwarn android.support.v7.internal.view.menu.**
-keep class !android.support.v7.internal.view.menu.**,android.support.** {*;}
# Error-prone annotations are compile-time only; Tink references them but they have no runtime presence
-dontwarn com.google.errorprone.annotations.**
# Tink (pulled in by androidx.security:security-crypto) — keep enough for EncryptedSharedPreferences
-dontwarn com.google.crypto.tink.**
