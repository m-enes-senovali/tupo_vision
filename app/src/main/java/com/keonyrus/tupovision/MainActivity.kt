package com.keonyrus.tupovision

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Accent = Color(0xFF19C37D)
private val Warning = Color(0xFFFFB454)

class MainActivity : ComponentActivity() {

  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { Engine.refreshEnvironment() }

  private val cameraPermission =
      registerForActivityResult(RequestPermission()) { granted ->
        if (granted) {
          Engine.mockUsePhoneCamera()
        } else {
          Engine.log("Sanal gözlük için telefon kamera izni verilmedi")
        }
      }

  // Camera access on the glasses is granted in the Meta AI app, which this launcher opens.
  private val permissionMutex = Mutex()
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val wearablesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
      }

  private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          permissionContinuation = continuation
          continuation.invokeOnCancellation { permissionContinuation = null }
          wearablesPermission.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val state by Engine.state.collectAsStateWithLifecycle()
      MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          TupoScreen(
              state = state,
              onGrantPermissions = ::requestAndroidPermissions,
              onInstallMetaAi = ::openMetaAiInStore,
              onRegister = { Engine.register(this) },
              onUnregister = { Engine.unregister(this) },
              onSignIn = { Engine.signIn(this) },
              onStart = { Engine.start(::requestWearablesPermission) },
              onCopyLog = ::copyLog,
              onNotificationAccess = ::openNotificationAccess,
              onOpenAppSettings = ::openAppSettings,
              onMockPhoneCamera = ::useMockBackCamera,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    Engine.refreshEnvironment()
  }

  private fun requestAndroidPermissions() {
    val wanted = ArrayList<String>()
    wanted.addAll(Engine.REQUIRED_PERMISSIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      wanted.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    androidPermissions.launch(wanted.toTypedArray())
  }

  private fun useMockBackCamera() {
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      Engine.mockUsePhoneCamera()
    } else {
      cameraPermission.launch(Manifest.permission.CAMERA)
    }
  }

  private fun openMetaAiInStore() {
    val page = "details?id=com.facebook.stella"
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://$page")))
    } catch (e: Exception) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$page")))
    }
  }

  private fun openNotificationAccess() {
    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
  }

  private fun openAppSettings() {
    runCatching {
      startActivity(
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
              .setData(Uri.fromParts("package", packageName, null)),
      )
    }
  }

  private fun copyLog() {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Tupo Vision TR günlüğü", Engine.logText()))
    Engine.log("Günlük panoya kopyalandı")
  }
}

@Composable
internal fun TupoScreen(
    state: UiState,
    onGrantPermissions: () -> Unit = {},
    onInstallMetaAi: () -> Unit = {},
    onRegister: () -> Unit = {},
    onUnregister: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onStart: () -> Unit = {},
    onCopyLog: () -> Unit = {},
    onNotificationAccess: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onMockPhoneCamera: () -> Unit = {},
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        "Tupo Vision TR",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )

    val error = state.error
    if (error != null) {
      Card(
          modifier =
              Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(error, color = Warning)
          TextButton(onClick = { Engine.clearError() }) { Text("Kapat") }
        }
      }
    }

    ConversationSection(state, onStart)
    if (MockBridge.isAvailable) {
      MockSection(state, onMockPhoneCamera)
    }
    GlassesSection(state, onGrantPermissions, onInstallMetaAi, onRegister, onUnregister)
    AssistantSection(state)
    if (!state.liveMode) ChatGptSection(state, onSignIn)
    SettingsSection(state, onNotificationAccess, onOpenAppSettings)
    LogSection(state, onCopyLog)
  }
}

@Composable
private fun ConversationSection(state: UiState, onStart: () -> Unit) {
  val assistantReady = if (state.liveMode) state.geminiKeySet else state.signedIn
  val ready = assistantReady && state.registered && state.permissionsGranted
  Section("Sohbet") {
    Button(
        onClick = { if (state.running) Engine.stop() else onStart() },
        enabled = state.running || ready,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
      Text(if (state.running) "Durdur" else "Sohbeti Başlat")
    }
    if (!ready && !state.running) {
      Text(
          "Önce aşağıdaki adımları tamamlayın: izinler, gözlük bağlantısı ve " +
              if (state.liveMode) "Gemini API anahtarı." else "ChatGPT girişi.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    val announcePhase = !(state.running && state.quietSessionAccessibility)
    Text(
        state.phase.label,
        style = MaterialTheme.typography.titleMedium,
        color = Accent,
        modifier =
            Modifier.semantics {
              if (announcePhase) liveRegion = LiveRegionMode.Polite
              contentDescription = "Sohbet durumu: ${state.phase.label}"
            },
    )
    if (state.partial.isNotEmpty()) Text("Dinlenen: ${state.partial}")
    if (state.question.isNotEmpty()) Text("Siz: ${state.question}")
    if (state.answer.isNotEmpty()) {
      Text((if (state.liveMode) "Gemini: " else "ChatGPT: ") + state.answer)
    }
    if (state.lastLocalScanSummary.isNotEmpty() && state.lastLocalScanSummary != state.answer) {
      Text(
          "Son yerel tarama: ${state.lastLocalScanSummary}",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    if (state.running || state.answer.isNotEmpty() || state.lastLocalScanSummary.isNotEmpty()) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
            onClick = { Engine.triggerLocalScanOnce() },
            enabled = state.running,
            modifier =
                Modifier.weight(1f).semantics {
                  contentDescription = "Gözlük görüntüsünü çevrimdışı metin ve barkod için tara"
                },
        ) {
          Text("Yerel Tara")
        }
        OutlinedButton(
            onClick = { Engine.repeatLastAnswer() },
            enabled = state.answer.isNotEmpty() || state.lastLocalScanSummary.isNotEmpty(),
            modifier =
                Modifier.weight(1f).semantics {
                  contentDescription = "Son yanıtı tekrar seslendir"
                },
        ) {
          Text("Son Yanıtı Tekrar Oku")
        }
      }
    }
    val latency = state.latencyMs
    if (latency != null) {
      Text(
          "İlk ses yanıt süresi: %.1f saniye".format(latency / 1000.0),
          style = MaterialTheme.typography.bodySmall,
      )
    }
    val frame = state.frame
    if (frame != null) {
      Text(
          if (state.liveMode) "Modelin şu an gördüğü kare:" else "Soruyla gönderilen kare:",
          style = MaterialTheme.typography.bodySmall,
      )
      Image(
          bitmap = frame.asImageBitmap(),
          contentDescription = "Gözlük kamerasından alınan son kare",
          modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
          contentScale = ContentScale.Fit,
      )
    }
    if (state.running) {
      Text(
          "Oturum: ${state.sessionState} · akış: ${state.streamState} · kare: ${state.frameInfo}",
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          "Gözlüğün sapına tek dokunuşla duraklatabilir, ikinci dokunuşla devam edebilirsiniz.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun MockSection(state: UiState, onMockPhoneCamera: () -> Unit) {
  Section("Sanal Gözlük Testi (Debug)") {
    StatusLine(state.mockActive, "Sanal gözlük: ${state.mockStatus}")
    Text(
        "Fiziksel gözlük olmadan geliştirici testleri için Meta MockDeviceKit kullanır.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!state.mockActive) {
      OutlinedButton(onClick = { Engine.enableMockGlasses() }) { Text("Sanal Gözlüğü Aç") }
    } else {
      OutlinedButton(onClick = { Engine.disableMockGlasses() }) { Text("Sanal Gözlüğü Kapat") }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(onClick = onMockPhoneCamera, modifier = Modifier.weight(1f)) {
          Text("Telefon Kamerasını Bağla")
        }
        OutlinedButton(
            onClick = { Engine.mockUseSamplePhoto() },
            modifier = Modifier.weight(1f),
        ) {
          Text("Örnek İlaç Etiketi")
        }
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
            onClick = { Engine.mockToggleWorn() },
            modifier = Modifier.weight(1f),
        ) {
          Text(if (state.mockWorn) "Gözlüğü Çıkar" else "Gözlüğü Tak")
        }
        OutlinedButton(
            onClick = { Engine.mockTriggerCapture(false) },
            modifier = Modifier.weight(1f),
        ) {
          Text("Capture Kısa Bas")
        }
      }
      OutlinedButton(onClick = { Engine.mockTriggerCapture(true) }) {
        Text("Capture Çift Bas (Duraklat / Devam)")
      }
    }
  }
}

@Composable
private fun GlassesSection(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
) {
  Section("1. Gözlük Bağlantısı") {
    StatusLine(state.permissionsGranted, "Bluetooth ve mikrofon izinleri")
    if (!state.permissionsGranted) {
      OutlinedButton(onClick = onGrantPermissions) { Text("İzinleri Ver") }
    }
    StatusLine(state.metaInstalled, "Meta AI uygulaması yüklü")
    if (!state.metaInstalled) {
      OutlinedButton(onClick = onInstallMetaAi) { Text("Google Play'de Meta AI'ı Aç") }
    }
    Text(
        "Önce gözlük açık ve bağlıyken Meta AI uygulamasında Geliştirici Modunu açın: " +
            "Ayarlar altında Uygulama Bilgileri bölümüne girin, Uygulama Sürümü satırına art arda 5 kez dokunun, " +
            "Geliştirici Modu anahtarını açıp Etkinleştir deyin. Aynı ekranda Device Access Toolkit yanında Yükle düğmesi varsa yükleyin. " +
            "Meta AI bağlantı sırasında hata verirse Geliştirici Modunu kapatıp yeniden açın, Meta AI uygulamasını tamamen kapatıp açın ve tekrar bağlayın.",
        style = MaterialTheme.typography.bodySmall,
    )
    StatusLine(state.registered, "Uygulama gözlüğe bağlı (${state.registration})")
    if (state.registered) {
      StatusLine(state.hasActiveDevice, "Gözlük aktif (cihaz sayısı: ${state.deviceCount})")
      TextButton(onClick = onUnregister) { Text("Bağlantıyı Kaldır") }
    } else {
      OutlinedButton(onClick = onRegister, enabled = state.permissionsGranted) {
        Text("Meta AI Üzerinden Gözlüğü Bağla")
      }
    }
  }
}

@Composable
private fun AssistantSection(state: UiState) {
  Section("2. Asistan") {
    SettingSwitch(
        title = "Canlı Ses Modu (Gemini Live)",
        subtitle =
            "Model sizi doğrudan duyar ve konuşur, sözünü kesebilirsiniz ve kamerayı sürekli izler. " +
                "Gemini API anahtarı gerekir. Kapalıyken ChatGPT aboneliğiniz kullanılır.",
        checked = state.liveMode,
        enabled = !state.running,
        onChange = { Engine.setLiveMode(it) },
    )
    SettingSwitch(
        title = "Canlı Web Araması",
        subtitle =
            "Güncel bilgiler için internette arama yapar (Gemini Live modunda Google Arama, ChatGPT modunda web araması). " +
                "Desteklenmeyen oturumlarda otomatik geri düşer.",
        checked = state.webSearch,
        enabled = !state.running,
        onChange = { Engine.setWebSearch(it) },
    )
    SettingRadioGroup(
        title = "Asistan Profili",
        subtitle =
            "Sorularınıza ve ortamınıza göre görüntü detayı ile sesli yönlendirmeyi ayarlar; sesli komutla da değiştirebilirsiniz.",
        options = AssistantProfile.entries,
        selected = state.assistantProfile,
        enabled = true,
        labelOf = { it.label },
        onSelect = { Engine.setAssistantProfile(it) },
    )
    if (state.liveMode) {
      StatusLine(state.geminiKeySet, "Gemini API anahtarı telefonda kayıtlı")
      if (state.geminiKeySet) {
        TextButton(onClick = { Engine.clearGeminiKey() }) { Text("Anahtarı Sil") }
      } else {
        var draft by remember { mutableStateOf("") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("aistudio.google.com üzerinden alınan API anahtarı") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            // A password field keeps the key out of keyboard suggestions and learning.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
              Engine.setGeminiKey(draft)
              draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
          Text("Anahtarı Kaydet")
        }
        Text(
            "Anahtar yalnızca bu telefonda şifreli saklanır ve sadece Google'a gönderilir.",
            style = MaterialTheme.typography.bodySmall,
        )
      }
      OutlinedTextField(
          value = state.geminiModel,
          onValueChange = { Engine.setGeminiModel(it.trim()) },
          label = { Text("Gemini Live modeli") },
          supportingText = {
            Text("Varsayılan: ${GeminiLive.DEFAULT_MODEL}. Sadece gerekirse değiştirin.")
          },
          singleLine = true,
          enabled = !state.running,
          modifier = Modifier.fillMaxWidth(),
      )
      SettingRadioGroup(
          title = "Gemini Live Sesi",
          subtitle = "Canlı ses oturumunda asistanın konuşma sesini seçin.",
          options = GeminiVoice.entries,
          selected = state.geminiVoice,
          enabled = !state.running,
          labelOf = { it.label },
          onSelect = { Engine.setGeminiVoice(it) },
      )
      SettingRadioGroup(
          title = "Konuşma Algılama Hassasiyeti (VAD)",
          subtitle = "Konuşmanızın bittiğini modelin ne kadar hızlı algılayacağını belirler.",
          options = VadSensitivity.entries,
          selected = state.vadSensitivity,
          enabled = !state.running,
          labelOf = { it.label },
          onSelect = { Engine.setVadSensitivity(it) },
      )
    } else {
      SettingRadioGroup(
          title = "ChatGPT Yanıt Hızı Profili",
          subtitle = "Hızlı tarama düşük gecikme ve küçük kare kullanır; detaylı belge yüksek doğruluk sunar.",
          options = ChatSpeedProfile.entries,
          selected = state.chatSpeedProfile,
          enabled = !state.running,
          labelOf = { it.label },
          onSelect = { Engine.setChatSpeedProfile(it) },
      )
    }
  }
}

@Composable
private fun ChatGptSection(state: UiState, onSignIn: () -> Unit) {
  Section("ChatGPT Aboneliği") {
    StatusLine(state.signedIn, "ChatGPT aboneliği bağlı")
    if (state.signedIn) {
      var expanded by remember { mutableStateOf(false) }
      Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier =
                Modifier.semantics {
                  contentDescription =
                      "ChatGPT modeli: ${state.model}. Değiştirmek için çift dokunun."
                },
        ) {
          Text("Model: ${state.model}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          state.models.forEach { id ->
            DropdownMenuItem(
                text = { Text(id) },
                onClick = {
                  Engine.setModel(id)
                  expanded = false
                },
            )
          }
        }
      }
      Text(
          "İstekler mevcut abonelik limitinizden kullanılır (chatgpt.com/settings/usage).",
          style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = { Engine.signOut() }) { Text("Çıkış Yap") }
    } else {
      Button(onClick = onSignIn) { Text("ChatGPT ile Devam Et") }
      if (state.loginInProgress) {
        Text(
            "Tarayıcıda giriş bekleniyor. Sekme kapandıysa düğmeye yeniden dokunun.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
      Text(
          "Go, Plus veya Pro planı gerekir. Giriş ekranında uygulamanın aboneliğinizi kullanmasına izin verin.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun SettingsSection(
    state: UiState,
    onNotificationAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
  Section("Ayarlar") {
    Text(
        "Aşağıdaki ayarları sohbet durdurulmuşken değiştirin; bir sonraki başlatmada geçerli olur.",
        style = MaterialTheme.typography.bodySmall,
    )
    SettingSwitch(
        title = "Sessiz Oturum Erişilebilirliği (Jieshuo / TalkBack)",
        subtitle =
            "Sohbet aktifken Dinliyor, Düşünüyor, Konuşuyor faz değişikliklerinin ekran okuyucu tarafından otomatik okunmasını durdurur; duruma dokunarak yine okuyabilirsiniz.",
        checked = state.quietSessionAccessibility,
        enabled = true,
        onChange = { Engine.setQuietSessionAccessibility(it) },
    )
    SettingSwitch(
        title = "Işık ve Bulanıklık Uyarısı (İsteğe Bağlı)",
        subtitle =
            "Varsayılan kapalıdır. Açıldığında çok karanlık veya bulanık karelerde kısa bilgi verir; isteği veya taramayı durdurmaz.",
        checked = state.qualityWarningEnabled,
        enabled = true,
        onChange = { Engine.setQualityWarningEnabled(it) },
    )
    SettingSwitch(
        title = "Bekleme Modu (Standby — Deneysel)",
        subtitle =
            "Telefonu çıkarmadan gözlükten başlatıp kapatmanızı sağlar. Meta AI içinde Basılı Tut hareketini Müzik olarak atayın. " +
                "Telefon kilitli veya ekranı kapalıyken müzik açıldığında uygulama müziği durdurur ve sohbeti açıp kapatır. " +
                "Telefon elinizdeyken veya araba/başka kulaklıkta açtığınız müziğe karışmaz. Gözlük takılıyken açın." +
                if (state.standbyAudio.isNotEmpty()) "\nİzlenen ses cihazı: ${state.standbyAudio}" else "",
        checked = state.standby,
        enabled = true,
        onChange = { Engine.setStandby(it) },
    )
    if (!state.mediaAccess) {
      OutlinedButton(onClick = onNotificationAccess) { Text("Bildirim Erişimi Ver") }
      Text(
          "Android yalnızca bu izinle hangi müzik uygulamasının çaldığını gösterir; Tupo Vision TR bildirim içeriği okumaz. " +
              "MIUI cihazlarda onay verirken 10 saniyelik güvenlik uyarısını onaylamanız gerekir. " +
              "Anahtar devre dışı görünüyorsa Android Ayarlar, Uygulamalar, Tupo Vision TR, Diğer seçenekler düğmesi, Kısıtlı ayarlara izin ver yolunu izleyin.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    if (state.installedMusicApps.isNotEmpty()) {
      var musicExpanded by remember { mutableStateOf(false) }
      Box {
        OutlinedButton(
            onClick = { musicExpanded = true },
            enabled = !state.standby,
            modifier =
                Modifier.semantics {
                  contentDescription =
                      "Standby müzik uygulaması seç. Şu anki paket: ${state.triggerPackage}"
                },
        ) {
          Text("Kurulu Müzik Uygulaması Seç")
        }
        DropdownMenu(expanded = musicExpanded, onDismissRequest = { musicExpanded = false }) {
          state.installedMusicApps.forEach { (pkg, label) ->
            DropdownMenuItem(
                text = { Text("$label ($pkg)") },
                onClick = {
                  Engine.setTriggerPackage(pkg)
                  musicExpanded = false
                },
            )
          }
        }
      }
    }
    OutlinedTextField(
        value = state.triggerPackage,
        onValueChange = { Engine.setTriggerPackage(it.trim()) },
        label = { Text("Standby müzik uygulaması paketi") },
        supportingText = {
          Text("Spotify için com.spotify.music kullanılır. Bekleme modu kapalıyken değiştirin.")
        },
        singleLine = true,
        enabled = !state.standby,
        modifier = Modifier.fillMaxWidth(),
    )
    SettingSwitch(
        title = "Gözlük Fiziksel Tuşları (Deneysel)",
        subtitle =
            "Gözlükteki capture tuşuna kısa basınca yüksek çözünürlüklü kare çeker, çift basınca sohbeti duraklatır veya sürdürür.",
        checked = state.physicalInputs,
        enabled = !state.running,
        onChange = { Engine.setPhysicalInputs(it) },
    )
    SettingRadioGroup(
        title = "Ses Profili",
        subtitle =
            "HD Ses, Jieshuo/TalkBack ve asistan sesini 48 kHz A2DP kalitesinde korur. Gözlük Mikrofonu ise Bluetooth SCO arama kanalını kullanır.",
        options = AudioProfile.entries,
        selected = state.audioProfile,
        enabled = !state.running,
        labelOf = { it.label },
        onSelect = { Engine.setAudioProfile(it) },
    )
    SettingSwitch(
        title = "Sesle Söz Kesme (Barge-in)",
        subtitle =
            "Yalnızca Canlı Ses Modu içindir. Model kendi sesini duyup sözünü kesiyorsa kapatın.",
        checked = state.bargeIn,
        enabled = !state.running,
        onChange = { Engine.setBargeIn(it) },
    )
    SettingSwitch(
        title = "Yüksek Çözünürlüklü Fotoğraf Modu",
        subtitle =
            "ChatGPT modunda her soruda video karesi yerine net fotoğraf çeker (küçük yazılar için daha nettir, biraz daha yavaştır). " +
                "Gemini Live'da sesle yakından oku diyerek veya capture tuşuyla da yüksek çözünürlüklü fotoğraf alabilirsiniz.",
        checked = state.photoMode,
        enabled = !state.running,
        onChange = { Engine.setPhotoMode(it) },
    )
    OutlinedTextField(
        value = state.language,
        onValueChange = { Engine.setLanguage(it.trim()) },
        label = { Text("Konuşma dili") },
        supportingText = {
          Text("Sesli yanıt ve konuşma tanıma dili (örneğin tr-TR, en-US).")
        },
        singleLine = true,
        enabled = !state.running,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.wakeWord,
        onValueChange = { Engine.setWakeWord(it) },
        label = { Text("Uyandırma kelimesi (isteğe bağlı)") },
        supportingText = {
          Text("Yalnızca ChatGPT modunda geçerlidir: sadece bu kelimeyi içeren cümleleri yanıtlar.")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = onOpenAppSettings) { Text("MIUI Pil ve Arka Plan Ayarlarını Aç") }
    Text(
        "Redmi Note 9 Pro gibi MIUI cihazlarda kesintisiz arka plan çalışması için Pil Tasarrufunu Kısıtlama yok yapın ve Otomatik Başlatmayı açın.",
        style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun LogSection(state: UiState, onCopyLog: () -> Unit) {
  var showLog by remember { mutableStateOf(false) }
  var frozenText by remember { mutableStateOf<String?>(null) }
  Section("Günlük") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      TextButton(
          onClick = {
            showLog = !showLog
            if (!showLog) frozenText = null
          },
      ) {
        Text(if (showLog) "Günlüğü Gizle" else "Günlüğü Göster")
      }
      TextButton(onClick = onCopyLog) { Text("Günlüğü Kopyala") }
      if (showLog) {
        TextButton(
            onClick = {
              frozenText =
                  if (frozenText == null) state.log.takeLast(60).joinToString("\n") else null
            },
        ) {
          Text(if (frozenText == null) "Görünümü Dondur" else "Canlıya Dön")
        }
      }
    }
    if (showLog) {
      val content = frozenText ?: state.log.takeLast(60).joinToString("\n")
      Text(
          content.ifEmpty { "Henüz günlük kaydı yok." },
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          lineHeight = 15.sp,
      )
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.semantics { heading() },
      )
      content()
    }
  }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
  val prefix = if (ok) "Tamam: " else "Eksik: "
  val desc = "$text. Durum: ${if (ok) "Tamamlandı" else "Eksik"}"
  Text(
      prefix + text,
      color = if (ok) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { contentDescription = desc },
  )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .toggleable(
                  value = checked,
                  enabled = enabled,
                  role = Role.Switch,
                  onValueChange = onChange,
              )
              .semantics(mergeDescendants = true) {
                stateDescription = if (checked) "Açık" else "Kapalı"
              }
              .padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        modifier = Modifier.clearAndSetSemantics {},
    )
  }
}

@Composable
private fun <T> SettingRadioGroup(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().selectableGroup(),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(subtitle, style = MaterialTheme.typography.bodySmall)
    options.forEach { option ->
      val isSelected = option == selected
      val label = labelOf(option)
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .selectable(
                      selected = isSelected,
                      enabled = enabled,
                      role = Role.RadioButton,
                      onClick = { onSelect(option) },
                  )
                  .semantics(mergeDescendants = true) {
                    stateDescription = if (isSelected) "Seçili" else "Seçili değil"
                    contentDescription = "$title, $label"
                  }
                  .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {},
        )
      }
    }
  }
}
