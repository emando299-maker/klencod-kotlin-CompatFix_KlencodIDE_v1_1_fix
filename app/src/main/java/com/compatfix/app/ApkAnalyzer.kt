package com.compatfix.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import java.util.zip.ZipFile
import java.io.File
import java.io.FileOutputStream

enum class FindingLevel { ERROR, WARNING, INFO }
data class Finding(val level: FindingLevel, val title: String, val detail: String, val action: String)
data class AnalysisReport(
    val sourceUri: Uri, val packageName: String, val versionName: String,
    val minSdk: Int, val targetSdk: Int, val abis: String,
    val features: String, val findings: List<Finding>
)

class ApkAnalyzer(private val context: Context) {
    suspend fun analyze(uri: Uri): AnalysisReport {
        val temp = File(context.cacheDir, "inspect_${System.currentTimeMillis()}.apk")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        try {
            require(temp.length() > 0) { "ملف APK فارغ" }
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(
                    temp.absolutePath,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(
                    temp.absolutePath,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
                )
            } ?: error("تعذر قراءة بيانات الحزمة. قد يكون الملف تالفاً أو ليس APK صالحاً.")
            val appInfo = info.applicationInfo
            val minSdk = if (Build.VERSION.SDK_INT >= 24) appInfo.minSdkVersion else 0
            val targetSdk = appInfo.targetSdkVersion
            val abis = mutableSetOf<String>()
            val features = mutableListOf<String>()
            ZipFile(temp).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/")) {
                        val parts = name.split("/")
                        if (parts.size >= 3 && parts[1].isNotBlank()) abis.add(parts[1])
                    }
                    if (name.endsWith(".so") && name.contains("arm64-v8a")) features.add("مكتبة ARM64")
                }
            }
            val findings = mutableListOf<Finding>()
            if (minSdk > Build.VERSION.SDK_INT) findings += Finding(FindingLevel.ERROR, "إصدار Android غير كافٍ",
                "التطبيق يطلب API $minSdk بينما جهازك يعمل بـ API ${Build.VERSION.SDK_INT}.", "لا يمكن خفض minSdk وحده؛ قد يعتمد التطبيق على واجهات أحدث.")
            else findings += Finding(FindingLevel.INFO, "إصدار Android", "minSdk=$minSdk، جهازك API ${Build.VERSION.SDK_INT}.", "لا يلزم تعديل بناءً على minSdk.")
            val supported = Build.SUPPORTED_ABIS.toSet()
            if (abis.isNotEmpty() && abis.none { it in supported }) findings += Finding(FindingLevel.ERROR, "عدم توافق المعمارية",
                "المكتبات الموجودة: ${abis.joinToString()}؛ معماريات الجهاز: ${supported.joinToString()}.", "يلزم إصدار يحتوي مكتبات متوافقة أو إعادة بناء المكتبات من المصدر.")
            else if (abis.isNotEmpty()) findings += Finding(FindingLevel.INFO, "معمارية المكتبات", "الموجود: ${abis.joinToString()}.", "توجد مطابقة مع معمارية مدعومة.")
            else findings += Finding(FindingLevel.WARNING, "لا توجد مكتبات native ظاهرة", "لم يعثر الفحص على مجلد lib داخل APK.", "قد يكون التطبيق Java/Kotlin خالصاً أو يستخدم split APKs.")
            if (targetSdk < 23) findings += Finding(FindingLevel.WARNING, "Target SDK قديم", "targetSdk=$targetSdk.", "قد تظهر سلوكيات توافق قديمة؛ لا ترفع القيمة دون اختبار.")
            findings += Finding(FindingLevel.INFO, "حدود الفحص", "تحليل أولي للحزمة وملفات ZIP؛ لا يفكك DEX ولا يحلل كل شروط Manifest الثنائية.", "استخدم سجل التثبيت/Logcat لتحديد سبب الرفض بدقة.")
            return AnalysisReport(uri, info.packageName, info.versionName ?: "غير معروف", minSdk, targetSdk,
                abis.joinToString().ifBlank { "لا يوجد" }, features.distinct().joinToString().ifBlank { "غير محدد" }, findings)
        } finally { temp.delete() }
    }
}
