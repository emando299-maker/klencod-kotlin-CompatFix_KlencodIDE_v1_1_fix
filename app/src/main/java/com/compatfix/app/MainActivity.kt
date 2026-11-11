package com.compatfix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var reportState by mutableStateOf<AnalysisReport?>(null)
    private var selectedName by mutableStateOf("لم يتم اختيار APK بعد")
    private var status by mutableStateOf("اختر ملف APK لبدء الفحص")
    private var exportUri by mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        selectedName = uri.lastPathSegment ?: "APK"
                        status = "جارٍ تحليل الملف..."
                        lifecycleScope.launch {
                            reportState = ApkAnalyzer(this@MainActivity).analyze(uri)
                            status = "اكتمل الفحص"
                        }
                    }
                }
                val saver = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
                ) { uri ->
                    if (uri != null && exportUri != null) {
                        lifecycleScope.launch {
                            runCatching {
                                contentResolver.openInputStream(exportUri!!)?.use { input ->
                                    contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                                } ?: error("تعذر قراءة الملف")
                            }.onSuccess { status = "تم تصدير نسخة APK كما هي دون تعديل" }
                             .onFailure { status = "فشل التصدير: ${it.message}" }
                        }
                    }
                }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("CompatFix", style = MaterialTheme.typography.headlineMedium)
                                Text("تحليل توافق تطبيقات Android")
                            }
                        }
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("فحص ملف APK", style = MaterialTheme.typography.titleLarge)
                                Text(selectedName)
                                Button(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp)); Text("اختيار ملف APK")
                                }
                                Text(status, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        reportState?.let { report ->
                            Text("نتيجة التحليل", style = MaterialTheme.typography.titleLarge)
                            InfoCard("الحزمة", report.packageName)
                            InfoCard("الإصدار", report.versionName)
                            InfoCard("SDK الأدنى / المستهدف", "${report.minSdk} / ${report.targetSdk}")
                            InfoCard("معماريات ARM / x86", report.abis.ifEmpty { "لم يتم العثور على مكتبات native" })
                            InfoCard("خصائص الجهاز المطلوبة", report.features.ifEmpty { "لم تُكتشف خصائص مطلوبة" })
                            Text("المشكلات والملاحظات", style = MaterialTheme.typography.titleMedium)
                            report.findings.forEach { finding ->
                                Card(colors = CardDefaults.cardColors(containerColor = when(finding.level) {
                                    FindingLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
                                    FindingLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                                    FindingLevel.INFO -> MaterialTheme.colorScheme.surfaceVariant
                                })) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(finding.title, style = MaterialTheme.typography.titleSmall)
                                        Text(finding.detail)
                                        Text("الإجراء: ${finding.action}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Card {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("الإصلاح التلقائي", style = MaterialTheme.typography.titleMedium)
                                    Text("هذا الإصدار ينفذ التشخيص الآمن فقط. لا يغيّر APK ولا يدّعي إصلاحه؛ تعديل Manifest أو DEX عشوائياً قد يمنع التثبيت أو تشغيل التطبيق.")
                                    OutlinedButton(onClick = {
                                        exportUri = report.sourceUri
                                        saver.launch("CompatFix_${report.packageName.substringAfterLast('.')}.apk")
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Build, contentDescription = null)
                                        Spacer(Modifier.width(8.dp)); Text("تصدير نسخة أصلية للفحص/النسخ الاحتياطي")
                                    }
                                }
                            }
                        }
                        Text("ملاحظة: فحص التطبيقات المثبتة وإعادة بناء APK وتوقيعه مراحل لاحقة. لا يمكن تجاوز نقص مكتبات أو وظائف نظام غير موجودة بمجرد تعديل بيانات الحزمة.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun InfoCard(title: String, value: String) {
    Card { Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(title, style = MaterialTheme.typography.labelMedium); Text(value) } }
}