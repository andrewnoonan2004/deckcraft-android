use std::cell::RefCell;
use std::collections::{HashMap, VecDeque};
use std::ffi::CStr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, Once, PoisonError};
use std::time::Duration;

use deckcraft_engine::Session;
use deckcraft_ui_egui::{Inbox, InboxEvent, InboxFile, Services, SlideApp};
use jni::JNIEnv;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jdouble, jfloat, jint, jlong, jstring};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

const MOD_ALT: i32 = 1;
const MOD_CTRL: i32 = 2;
const MOD_SHIFT: i32 = 4;
const MOD_MAC_CMD: i32 = 8;
const MOD_COMMAND: i32 = 16;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PickerRequestDto {
    pub id: i64,
    pub mode: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suggested_name: Option<String>,
    pub extensions: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedFileEventDto {
    pub path: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrintJobRequestDto {
    pub job_name: String,
    pub pdf_path: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImeRectDto {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlatformEffectsDto {
    pub cursor_icon: String,
    pub wants_ime: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ime_rect: Option<ImeRectDto>,
    pub repaint_after_ms: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub copy_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub open_url: Option<String>,
    pub window_title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_path: Option<String>,
    pub has_unsaved_changes: bool,
    pub close_requested: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub picker_request: Option<PickerRequestDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub saved_file: Option<SavedFileEventDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub print_job: Option<PrintJobRequestDto>,
}

impl Default for PlatformEffectsDto {
    fn default() -> Self {
        Self {
            cursor_icon: "default".to_string(),
            wants_ime: false,
            ime_rect: None,
            repaint_after_ms: -1,
            copy_text: None,
            open_url: None,
            window_title: "DeckCraft".to_string(),
            active_path: Some(String::new()),
            has_unsaved_changes: false,
            close_requested: false,
            picker_request: None,
            saved_file: None,
            print_job: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PersistedSettingsBundle {
    ui: deckcraft_ui_egui::UiState,
    prefs: deckcraft_engine::Prefs,
}

#[derive(Debug, Clone)]
struct PendingOpenPick {
    id: u64,
    purpose: String,
}

#[derive(Debug, Clone)]
struct StagedSavePick {
    id: u64,
    suggested_name: String,
    staged_path: PathBuf,
    mark_document_saved: bool,
}

#[derive(Default)]
struct BridgeAsyncState {
    picker_requests: VecDeque<PickerRequestDto>,
    pending_opens: Vec<PendingOpenPick>,
    staged_saves: Vec<StagedSavePick>,
    pending_downloads: Vec<(String, Vec<u8>)>,
}

pub const NATIVE_CRASH_FILE_NAME: &str = "native_crash.txt";

static NATIVE_CRASH_LOG: LazyLock<Mutex<Option<PathBuf>>> = LazyLock::new(|| Mutex::new(None));
static PRINT_COUNTER: AtomicU64 = AtomicU64::new(1);
static BRIDGE_REQ_COUNTER: AtomicU64 = AtomicU64::new(1_000_000);
static INIT_ONCE: Once = Once::new();

#[cfg(target_os = "android")]
fn record_native_panic(message: &str) {
    let path = NATIVE_CRASH_LOG
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .clone();
    if let Some(path) = path {
        let thread = std::thread::current();
        let name = thread.name().unwrap_or("<unnamed>");
        let backtrace = std::backtrace::Backtrace::force_capture();
        let body =
            format!("Native panic on thread '{name}':\n{message}\n\nBacktrace:\n{backtrace}\n");
        let _ = std::fs::write(path, body);
    }
}

fn ensure_global_hooks_installed() {
    INIT_ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("DeckCraftRust"),
            );
            std::panic::set_hook(Box::new(|info| {
                log::error!("DeckCraft native panic caught: {info}");
                record_native_panic(&info.to_string());
            }));
        }
    });
}

fn extensions_for_purpose(purpose: &str) -> Vec<String> {
    match purpose {
        "picture" => vec!["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"],
        "audio" => vec!["mp3", "wav", "m4a", "aac", "ogg", "opus", "flac"],
        "video" => vec!["mp4", "mov", "webm", "m4v", "mkv"],
        "theme" => vec!["deckcraft", "pptx", "potx", "thmx"],
        _ => vec!["deckcraft", "pptx", "potx", "ppsx", "txt", "md", "rtf"],
    }
    .into_iter()
    .map(String::from)
    .collect()
}

fn extensions_for_filename(name: &str) -> Vec<String> {
    if let Some(ext) = Path::new(name)
        .extension()
        .and_then(|e| e.to_str())
        .map(|s| s.trim_start_matches('.').to_ascii_lowercase())
        .filter(|s| !s.is_empty())
    {
        vec![ext]
    } else {
        vec!["deckcraft".to_string()]
    }
}

fn is_presentation_file(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".deckcraft")
        || lower.ends_with(".slidecraft")
        || lower.ends_with(".pptx")
        || lower.ends_with(".potx")
        || lower.ends_with(".ppsx")
}

enum QueuedInput {
    PointerMoved {
        x_native_pt: f32,
        y_native_pt: f32,
    },
    PointerGone,
    PointerButton {
        x_native_pt: f32,
        y_native_pt: f32,
        button: i32,
        pressed: bool,
        modifiers: i32,
    },
    MouseWheel {
        delta_x: f32,
        delta_y: f32,
        is_line_unit: bool,
        modifiers: i32,
    },
    Touch {
        device_id: i32,
        pointer_id: i32,
        phase: i32,
        x_native_pt: f32,
        y_native_pt: f32,
        force: f32,
    },
    Zoom {
        delta: f32,
    },
    Key {
        key_code: i32,
        pressed: bool,
        repeat: bool,
        modifiers: i32,
    },
    Text(String),
}

#[derive(Default)]
struct PendingHostQueue {
    inputs: Vec<QueuedInput>,
    commands: Vec<String>,
    external_opens: Vec<(String, String)>,
    picked_files: Vec<(u64, Vec<String>)>,
    ui_scale_override: Option<f32>,
}

struct ThreadBoundCore {
    app: SlideApp,
    ctx: egui::Context,
    inbox: Inbox,
    async_state: Arc<Mutex<BridgeAsyncState>>,
    painter: Option<egui_glow::Painter>,
    applied_modifiers: egui::Modifiers,
    files_dir: String,
    cache_dir: String,
    saved_paths: VecDeque<String>,
    print_jobs: VecDeque<PrintJobRequestDto>,
    last_journal_len: usize,
    in_save_dialog: bool,
}

impl ThreadBoundCore {
    fn new(
        files_dir: &str,
        cache_dir: &str,
        initial_prefs_json: Option<&str>,
        wake_flag: Arc<AtomicBool>,
    ) -> Self {
        let ctx = egui::Context::default();
        deckcraft_ui_egui::install_ime_pass_hooks(&ctx);
        ctx.set_request_repaint_callback(move |_info| {
            wake_flag.store(true, Ordering::SeqCst);
        });

        let inbox: Inbox = Inbox::default();
        let async_state = Arc::new(Mutex::new(BridgeAsyncState::default()));

        let open_state = Arc::clone(&async_state);
        let download_state = Arc::clone(&async_state);

        let services = Services {
            inbox: Some(inbox.clone()),
            open_async: Some(Box::new(move |purpose: &str, id: u64| {
                let mut guard = open_state.lock().unwrap_or_else(PoisonError::into_inner);
                guard.pending_opens.push(PendingOpenPick {
                    id,
                    purpose: purpose.to_string(),
                });
                guard.picker_requests.push_back(PickerRequestDto {
                    id: id as i64,
                    mode: "open_file".to_string(),
                    title: Some(format!("Open ({purpose})")),
                    suggested_name: None,
                    extensions: extensions_for_purpose(purpose),
                });
            })),
            download: Some(Box::new(move |name: &str, bytes: &[u8]| {
                let mut guard = download_state
                    .lock()
                    .unwrap_or_else(PoisonError::into_inner);
                guard
                    .pending_downloads
                    .push((name.to_string(), bytes.to_vec()));
            })),
            read: Some(Box::new(|p: &str| {
                std::fs::read(p).map_err(|e| e.to_string())
            })),
            write: Some(Box::new(|p: &str, b: &[u8]| {
                std::fs::write(p, b).map_err(|e| e.to_string())
            })),
            ..Default::default()
        };

        let mut session = Session::new();
        if !files_dir.is_empty() {
            let recovery_dir = PathBuf::from(files_dir).join("recovery");
            let _ = std::fs::create_dir_all(&recovery_dir);
            session.recovery_dir = Some(recovery_dir);
        }

        let mut app = SlideApp::new(session, services);
        app.integrated_titlebar = false;

        if !files_dir.is_empty() {
            let settings_dir = PathBuf::from(files_dir).join("settings");
            let (ui, prefs) = deckcraft_ui_egui::settings::load(&settings_dir);
            if let Some(ui) = ui {
                app.ui = ui;
            }
            if let Some(prefs) = prefs {
                app.session.prefs = prefs;
            }
            if let Ok(v) = app.session.execute("file.recovery.open", &json!({}))
                && let Some(n) = v
                    .get("opened")
                    .and_then(|o| o.as_array())
                    .map(Vec::len)
                    .filter(|n| *n > 0)
            {
                app.set_status(format!(
                    "Recovered {n} unsaved presentation{} from the last session",
                    if n == 1 { "" } else { "s" }
                ));
            }
        }

        let mut core = Self {
            last_journal_len: app.session.journal.len(),
            app,
            ctx,
            inbox,
            async_state,
            painter: None,
            applied_modifiers: egui::Modifiers::NONE,
            files_dir: files_dir.to_string(),
            cache_dir: cache_dir.to_string(),
            saved_paths: VecDeque::new(),
            print_jobs: VecDeque::new(),
            in_save_dialog: false,
        };

        if let Some(json_str) = initial_prefs_json.filter(|s| !s.trim().is_empty()) {
            core.restore_state(json_str);
        }

        core
    }

    fn persist_state(&self) -> String {
        if !self.files_dir.is_empty() {
            let settings_dir = PathBuf::from(&self.files_dir).join("settings");
            deckcraft_ui_egui::settings::save(
                &settings_dir,
                &self.app.ui,
                &self.app.session.prefs,
            );
        }
        let bundle = PersistedSettingsBundle {
            ui: self.app.ui.clone(),
            prefs: self.app.session.prefs.clone(),
        };
        serde_json::to_string(&bundle).unwrap_or_default()
    }

    fn restore_state(&mut self, state_json: &str) {
        let trimmed = state_json.trim();
        if trimmed.is_empty() {
            return;
        }
        if let Ok(bundle) = serde_json::from_str::<PersistedSettingsBundle>(trimmed) {
            self.app.ui = bundle.ui;
            self.app.session.prefs = bundle.prefs.sanitized();
            deckcraft_ui_egui::theme::apply(
                &self.ctx,
                &deckcraft_ui_egui::theme::Tokens::for_brightness(self.app.ui.brightness),
            );
            if !self.files_dir.is_empty() {
                let settings_dir = PathBuf::from(&self.files_dir).join("settings");
                deckcraft_ui_egui::settings::save(
                    &settings_dir,
                    &self.app.ui,
                    &self.app.session.prefs,
                );
            }
        }
    }

    fn record_saved_path(&mut self, path: String) {
        if path.is_empty() {
            return;
        }
        if !self.saved_paths.iter().any(|p| p == &path) {
            self.saved_paths.push_back(path);
        }
    }

    fn sync_journal_saves(&mut self) {
        let len = self.app.session.journal.len();
        if len < self.last_journal_len {
            self.last_journal_len = 0;
        }
        let active_path = self.app.session.active().and_then(|d| d.path.clone());
        let mut discovered = Vec::new();
        for (cmd_id, params) in &self.app.session.journal[self.last_journal_len..len] {
            match cmd_id.as_str() {
                "file.save" => {
                    if let Some(p) = params
                        .get("path")
                        .and_then(Value::as_str)
                        .map(String::from)
                        .or_else(|| active_path.clone())
                    {
                        discovered.push(p);
                    }
                }
                "file.saveAs" | "file.export" => {
                    if let Some(p) = params.get("path").and_then(Value::as_str) {
                        discovered.push(p.to_string());
                    }
                }
                _ => {}
            }
        }
        self.last_journal_len = len;
        for path in discovered {
            self.record_saved_path(path);
        }
    }

    fn drain_pending_downloads(&mut self, force_print: bool) {
        let downloads = {
            let mut guard = self
                .async_state
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            std::mem::take(&mut guard.pending_downloads)
        };
        if downloads.is_empty() {
            return;
        }

        let on_print_page = self
            .app
            .backstage
            .as_ref()
            .is_some_and(|b| b.page == deckcraft_ui_egui::backstage::Page::Print);
        let on_export_page = self
            .app
            .backstage
            .as_ref()
            .is_some_and(|b| b.page == deckcraft_ui_egui::backstage::Page::Export)
            || self
                .app
                .dialog
                .as_ref()
                .is_some_and(|d| d.id == "export");

        let base_cache = if !self.cache_dir.is_empty() {
            PathBuf::from(&self.cache_dir)
        } else {
            std::env::temp_dir()
        };

        for (suggested_name, bytes) in downloads {
            let is_pdf = suggested_name.to_ascii_lowercase().ends_with(".pdf");
            if (force_print || on_print_page) && is_pdf {
                let seq = PRINT_COUNTER.fetch_add(1, Ordering::SeqCst);
                let spool_dir = base_cache.join("print_spool");
                let _ = std::fs::create_dir_all(&spool_dir);
                let pdf_path = spool_dir.join(format!("print_job_{seq}.pdf"));
                if std::fs::write(&pdf_path, &bytes).is_ok() {
                    self.print_jobs.push_back(PrintJobRequestDto {
                        job_name: suggested_name,
                        pdf_path: pdf_path.to_string_lossy().into_owned(),
                    });
                }
            } else {
                let request_id = BRIDGE_REQ_COUNTER.fetch_add(1, Ordering::SeqCst);
                let staged_dir = base_cache.join("staged_saves");
                let _ = std::fs::create_dir_all(&staged_dir);
                let safe_name: String = suggested_name
                    .chars()
                    .map(|c| {
                        if c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' {
                            c
                        } else {
                            '_'
                        }
                    })
                    .collect();
                let staged_path = staged_dir.join(format!("staged_{request_id}_{safe_name}"));
                if std::fs::write(&staged_path, &bytes).is_ok() {
                    let extensions = extensions_for_filename(&suggested_name);
                    let mark_document_saved = self.in_save_dialog
                        || (!on_export_page && is_presentation_file(&suggested_name));
                    let mut guard = self
                        .async_state
                        .lock()
                        .unwrap_or_else(PoisonError::into_inner);
                    guard.staged_saves.push(StagedSavePick {
                        id: request_id,
                        suggested_name: suggested_name.clone(),
                        staged_path,
                        mark_document_saved,
                    });
                    guard.picker_requests.push_back(PickerRequestDto {
                        id: request_id as i64,
                        mode: "save_file".to_string(),
                        title: Some("Save As".to_string()),
                        suggested_name: Some(suggested_name),
                        extensions,
                    });
                }
            }
        }
    }

    fn open_path(&mut self, local_path: &str) {
        let trimmed = local_path.trim();
        if trimmed.is_empty() {
            return;
        }
        if is_presentation_file(trimmed) {
            let paths = vec![trimmed.to_string()];
            let failed = self.app.open_from_system(&paths);
            for (_, err) in failed {
                self.app.notify(err);
            }
            self.last_journal_len = self.app.session.journal.len();
        } else if let Ok(bytes) = std::fs::read(trimmed) {
            let name = Path::new(trimmed)
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_else(|| trimmed.to_string());
            self.app.open_or_insert_bytes(&name, &bytes);
            self.last_journal_len = self.app.session.journal.len();
        } else {
            self.app.notify(format!("Could not read {trimmed}"));
        }
    }

    fn execute_host_command(&mut self, raw_cmd: &str) {
        let trimmed = raw_cmd.trim();
        if trimmed.is_empty() {
            return;
        }
        if trimmed == "file.save" {
            self.in_save_dialog = true;
            self.app.save();
            self.sync_journal_saves();
            self.drain_pending_downloads(false);
            self.in_save_dialog = false;
            return;
        }

        let normalized_id = match trimmed {
            "app.command_palette" | "view.palette" => "app.palette",
            "file.open" => "app.openDialog",
            "file.save_as" | "file.saveAs" => "app.saveAsDialog",
            "edit.find" => "app.find",
            "edit.replace" => "app.replace",
            other => other,
        };

        let is_save_as = normalized_id == "app.saveAsDialog";
        let is_print = normalized_id == "app.print";
        if is_save_as {
            self.in_save_dialog = true;
        }
        let _ = self.app.run(normalized_id, json!({}));
        self.sync_journal_saves();
        self.drain_pending_downloads(is_print);
        self.in_save_dialog = false;
    }

    fn apply_picked_files(&mut self, request_id: u64, paths: Vec<String>) {
        let staged_save = {
            let mut guard = self
                .async_state
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            guard
                .staged_saves
                .iter()
                .position(|s| s.id == request_id)
                .map(|idx| guard.staged_saves.remove(idx))
        };

        if let Some(staged) = staged_save {
            if let Some(dest_path) = paths.into_iter().find(|p| !p.trim().is_empty()) {
                if let Some(parent) = Path::new(&dest_path).parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                if std::fs::copy(&staged.staged_path, &dest_path).is_ok() {
                    if staged.mark_document_saved {
                        let _ = deckcraft_engine::cmd::file::mark_saved(
                            &mut self.app.session,
                            Some(dest_path.clone()),
                        );
                        self.app.ui.recent.retain(|p| p != &dest_path);
                        self.app.ui.recent.insert(0, dest_path.clone());
                        self.app
                            .set_status(format!("Saved {}", staged.suggested_name));
                    } else {
                        self.app
                            .set_status(format!("Exported {}", staged.suggested_name));
                    }
                    self.record_saved_path(dest_path);
                }
            }
            let _ = std::fs::remove_file(&staged.staged_path);
            return;
        }

        let open_pick = {
            let mut guard = self
                .async_state
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            guard
                .pending_opens
                .iter()
                .position(|p| p.id == request_id)
                .map(|idx| guard.pending_opens.remove(idx))
        };

        let purpose = open_pick
            .as_ref()
            .map(|p| p.purpose.as_str())
            .unwrap_or("open");

        let chosen = paths.into_iter().find(|p| !p.trim().is_empty());
        match chosen {
            None => {
                if let Ok(mut guard) = self.inbox.lock() {
                    guard.push(InboxEvent::Cancelled { id: request_id });
                }
            }
            Some(local_path) => {
                if purpose == "open" && is_presentation_file(&local_path) {
                    if let Ok(mut guard) = self.inbox.lock() {
                        guard.push(InboxEvent::Cancelled { id: request_id });
                    }
                    if let Err(e) = self.app.open_path(&local_path) {
                        self.app.notify(e);
                    }
                } else {
                    match std::fs::read(&local_path) {
                        Ok(bytes) => {
                            let name = Path::new(&local_path)
                                .file_name()
                                .map(|n| n.to_string_lossy().to_string())
                                .unwrap_or_else(|| local_path.clone());
                            if let Ok(mut guard) = self.inbox.lock() {
                                guard.push(InboxEvent::File(InboxFile {
                                    id: request_id,
                                    name,
                                    bytes,
                                }));
                            }
                        }
                        Err(e) => {
                            if let Ok(mut guard) = self.inbox.lock() {
                                guard.push(InboxEvent::Cancelled { id: request_id });
                            }
                            self.app.notify(format!("{local_path}: {e}"));
                        }
                    }
                }
            }
        }
        self.app.logic(&self.ctx);
        self.last_journal_len = self.app.session.journal.len();
    }

    fn run_single_pass(&mut self, mut raw: egui::RawInput) -> egui::FullOutput {
        let ctx = self.ctx.clone();
        self.app.raw_input_hook(&ctx, &mut raw);
        ctx.run_ui(raw, |ui| {
            self.app.logic(ui.ctx());
            self.app.ui(ui);
        })
    }
}

pub struct EngineEntry {
    id: i64,
    files_dir: String,
    cache_dir: String,
    initial_prefs_json: Option<String>,
    queue: Mutex<PendingHostQueue>,
    wake_requested: Arc<AtomicBool>,
    persisted_cache: Mutex<String>,
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: LazyLock<Mutex<HashMap<i64, Arc<EngineEntry>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

thread_local! {
    static THREAD_CORES: RefCell<HashMap<i64, ThreadBoundCore>> = RefCell::new(HashMap::new());
}

fn get_engine(handle: i64) -> Option<Arc<EngineEntry>> {
    if handle <= 0 {
        return None;
    }
    let map = ENGINES.lock().unwrap_or_else(PoisonError::into_inner);
    map.get(&handle).cloned()
}

impl EngineEntry {
    pub fn new(files_dir: &str, cache_dir: &str, initial_prefs_json: Option<&str>) -> Arc<Self> {
        let id = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
        Self::with_id(id, files_dir, cache_dir, initial_prefs_json)
    }

    fn with_id(
        id: i64,
        files_dir: &str,
        cache_dir: &str,
        initial_prefs_json: Option<&str>,
    ) -> Arc<Self> {
        if !files_dir.is_empty() {
            let _ = std::fs::create_dir_all(files_dir);
            *NATIVE_CRASH_LOG.lock().unwrap_or_else(PoisonError::into_inner) =
                Some(PathBuf::from(files_dir).join(NATIVE_CRASH_FILE_NAME));
        }
        if !cache_dir.is_empty() {
            let _ = std::fs::create_dir_all(cache_dir);
        }
        ensure_global_hooks_installed();

        let seed_cache = initial_prefs_json
            .filter(|s| !s.trim().is_empty())
            .map(ToOwned::to_owned)
            .unwrap_or_default();

        Arc::new(Self {
            id,
            files_dir: files_dir.to_string(),
            cache_dir: cache_dir.to_string(),
            initial_prefs_json: initial_prefs_json.map(ToOwned::to_owned),
            queue: Mutex::new(PendingHostQueue::default()),
            wake_requested: Arc::new(AtomicBool::new(true)),
            persisted_cache: Mutex::new(seed_cache),
        })
    }

    pub fn handle(&self) -> i64 {
        self.id
    }

    pub fn request_wake(&self) {
        self.wake_requested.store(true, Ordering::SeqCst);
    }

    pub fn consume_wake(&self) -> bool {
        self.wake_requested.swap(false, Ordering::SeqCst)
    }

    fn enqueue_input(&self, item: QueuedInput) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.inputs.push(item);
        }
        self.request_wake();
    }

    pub fn execute_command(&self, command_id: String) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.commands.push(command_id);
        }
        self.request_wake();
    }

    pub fn open_external_file(&self, display_name: String, local_path: String) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.external_opens.push((display_name, local_path));
        }
        self.request_wake();
    }

    pub fn deliver_picked_files(&self, request_id: u64, paths: Vec<String>) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.picked_files.push((request_id, paths));
        }
        self.request_wake();
    }

    pub fn set_ui_scale_override(&self, scale: f32) {
        if scale.is_finite() && (0.5..=3.0).contains(&scale) {
            {
                let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
                q.ui_scale_override = Some(scale);
            }
            self.request_wake();
        }
    }

    pub fn persist_state(&self) -> String {
        let from_thread_local = THREAD_CORES.with(|cell| {
            cell.borrow().get(&self.id).map(|core| core.persist_state())
        });
        if let Some(fresh) = from_thread_local {
            *self
                .persisted_cache
                .lock()
                .unwrap_or_else(PoisonError::into_inner) = fresh.clone();
            return fresh;
        }
        self.persisted_cache
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    fn ensure_painter_on_gl_thread(&self) {
        THREAD_CORES.with(|cell| {
            let mut map = cell.borrow_mut();
            let core = map.entry(self.id).or_insert_with(|| {
                ThreadBoundCore::new(
                    &self.files_dir,
                    &self.cache_dir,
                    self.initial_prefs_json.as_deref(),
                    Arc::clone(&self.wake_requested),
                )
            });
            if let Some(mut old) = core.painter.take() {
                old.destroy();
            }
            match create_gles_painter() {
                Ok(painter) => {
                    core.painter = Some(painter);
                }
                Err(e) => {
                    log::error!("Failed to initialize egui_glow Painter for {}: {e}", self.id);
                }
            }
        });
        self.request_wake();
    }

    fn destroy_painter_on_gl_thread(&self) {
        THREAD_CORES.with(|cell| {
            if let Some(core) = cell.borrow_mut().get_mut(&self.id)
                && let Some(mut painter) = core.painter.take()
            {
                painter.destroy();
            }
        });
    }

    pub fn step_headless_or_gl(
        &self,
        paint_gl: bool,
        time_secs: f64,
        width_px: u32,
        height_px: u32,
        pixels_per_point: f32,
        dark_mode: bool,
        focused: bool,
    ) -> PlatformEffectsDto {
        let safe_width = width_px.max(1);
        let safe_height = height_px.max(1);
        let native_ppp = if pixels_per_point.is_finite() && pixels_per_point > 0.05 {
            pixels_per_point
        } else {
            1.0
        };

        let drained = {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            PendingHostQueue {
                inputs: std::mem::take(&mut q.inputs),
                commands: std::mem::take(&mut q.commands),
                external_opens: std::mem::take(&mut q.external_opens),
                picked_files: std::mem::take(&mut q.picked_files),
                ui_scale_override: q.ui_scale_override.take(),
            }
        };

        let had_mutations = !drained.commands.is_empty()
            || !drained.external_opens.is_empty()
            || !drained.picked_files.is_empty()
            || drained.ui_scale_override.is_some();

        let (effects, updated_persist) = THREAD_CORES.with(|cell| {
            let mut map = cell.borrow_mut();
            let core = map.entry(self.id).or_insert_with(|| {
                ThreadBoundCore::new(
                    &self.files_dir,
                    &self.cache_dir,
                    self.initial_prefs_json.as_deref(),
                    Arc::clone(&self.wake_requested),
                )
            });

            if let Some(scale) = drained.ui_scale_override {
                core.app.ui.ui_scale = scale.clamp(0.5, 3.0);
            }

            for (_display_name, local_path) in drained.external_opens {
                if !local_path.trim().is_empty() {
                    core.open_path(&local_path);
                }
            }

            for (request_id, paths) in drained.picked_files {
                core.apply_picked_files(request_id, paths);
            }

            for raw_cmd in drained.commands {
                core.execute_host_command(&raw_cmd);
            }

            let zoom_factor = core.ctx.zoom_factor().max(0.1);
            let effective_ppp = (native_ppp * zoom_factor).max(0.1);
            let width_pt = safe_width as f32 / effective_ppp;
            let height_pt = safe_height as f32 / effective_ppp;

            let mut events = Vec::with_capacity(drained.inputs.len() * 2);
            for item in drained.inputs {
                translate_queued_input(
                    item,
                    zoom_factor,
                    &mut core.applied_modifiers,
                    &mut events,
                );
            }

            let make_raw_input = |pass_events: Vec<egui::Event>, t: f64| {
                let mut raw = egui::RawInput {
                    screen_rect: Some(egui::Rect::from_min_size(
                        egui::Pos2::ZERO,
                        egui::Vec2::new(width_pt, height_pt),
                    )),
                    time: Some(t.max(0.0)),
                    events: pass_events,
                    focused,
                    system_theme: Some(if dark_mode {
                        egui::Theme::Dark
                    } else {
                        egui::Theme::Light
                    }),
                    ..Default::default()
                };
                if let Some(vp) = raw.viewports.get_mut(&egui::ViewportId::ROOT) {
                    vp.native_pixels_per_point = Some(native_ppp);
                    vp.focused = Some(focused);
                    vp.inner_rect = raw.screen_rect;
                }
                raw
            };

            let mut full_output = core.run_single_pass(make_raw_input(events, time_secs));

            // First-frame warmup: SlideApp installs custom interface fonts on pass 1 and renders UI
            // starting on pass 2 (`fonts_ready = true`). Merging `textures_delta` renders on frame 1!
            if !full_output.textures_delta.is_empty() && full_output.shapes.is_empty() {
                let mut warmup_delta = std::mem::take(&mut full_output.textures_delta);
                let second_out =
                    core.run_single_pass(make_raw_input(Vec::new(), time_secs + 0.001));
                warmup_delta.append(second_out.textures_delta);
                full_output = egui::FullOutput {
                    textures_delta: warmup_delta,
                    ..second_out
                };
            }

            core.sync_journal_saves();
            core.drain_pending_downloads(false);

            if paint_gl
                && let Some(painter) = core.painter.as_mut()
            {
                let clipped_primitives = core
                    .ctx
                    .tessellate(std::mem::take(&mut full_output.shapes), full_output.pixels_per_point);
                let clear_rgba = if dark_mode {
                    [0.098_f32, 0.106, 0.122, 1.0]
                } else {
                    [0.945_f32, 0.949, 0.957, 1.0]
                };
                painter.clear([safe_width, safe_height], clear_rgba);
                painter.paint_and_update_textures(
                    [safe_width, safe_height],
                    full_output.pixels_per_point,
                    &clipped_primitives,
                    &mut full_output.textures_delta,
                );
            } else {
                full_output.textures_delta.clear();
            }

            let fx = extract_platform_effects(core, full_output);
            let persisted = if had_mutations || fx.saved_file.is_some() || fx.close_requested {
                Some(core.persist_state())
            } else {
                None
            };
            (fx, persisted)
        });

        if let Some(json_str) = updated_persist {
            *self
                .persisted_cache
                .lock()
                .unwrap_or_else(PoisonError::into_inner) = json_str;
        }
        effects
    }
}

fn extract_platform_effects(
    core: &mut ThreadBoundCore,
    full_output: egui::FullOutput,
) -> PlatformEffectsDto {
    let cursor_icon = map_cursor_icon(full_output.platform_output.cursor_icon).to_string();

    let (wants_ime, ime_rect) = match full_output.platform_output.ime {
        Some(ime) => (
            true,
            Some(ImeRectDto {
                x: ime.rect.min.x,
                y: ime.rect.min.y,
                width: ime.rect.width(),
                height: ime.rect.height(),
            }),
        ),
        None => (false, None),
    };

    let mut copy_text: Option<String> = None;
    let mut open_url: Option<String> = None;
    for cmd in full_output.platform_output.commands {
        match cmd {
            egui::OutputCommand::CopyText(text) => {
                arboard::android_set_clipboard_text(text.clone());
                copy_text = Some(text);
            }
            egui::OutputCommand::CopyImage(color_image) => {
                if let Ok(mut cb) = arboard::Clipboard::new() {
                    let bytes: Vec<u8> = color_image
                        .pixels
                        .iter()
                        .flat_map(|c| c.to_array())
                        .collect();
                    let _ = cb.set_image(arboard::ImageData {
                        width: color_image.size[0],
                        height: color_image.size[1],
                        bytes: std::borrow::Cow::Owned(bytes),
                    });
                }
            }
            egui::OutputCommand::OpenUrl(url_cmd) => {
                open_url = Some(url_cmd.url);
            }
            _ => {}
        }
    }
    if copy_text.is_none() {
        copy_text = arboard::android_take_copied_text();
    } else {
        let _ = arboard::android_take_copied_text();
    }

    let mut repaint_after_ms: i64 = -1;
    let mut close_requested = false;
    let mut cancel_close = false;

    if let Some(root_out) = full_output.viewport_output.get(&egui::ViewportId::ROOT) {
        repaint_after_ms = duration_to_repaint_ms(root_out.repaint_delay);
        for cmd in &root_out.commands {
            match cmd {
                egui::ViewportCommand::Close => close_requested = true,
                egui::ViewportCommand::CancelClose => cancel_close = true,
                _ => {}
            }
        }
    }
    if cancel_close {
        close_requested = false;
    }

    let picker_request = {
        let mut guard = core
            .async_state
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        guard.picker_requests.pop_front()
    };

    let saved_file = core
        .saved_paths
        .pop_front()
        .map(|path| SavedFileEventDto { path });

    let print_job = core.print_jobs.pop_front();

    let window_title = core
        .app
        .session
        .active()
        .map(|d| format!("{} — DeckCraft", d.title()))
        .unwrap_or_else(|| "DeckCraft".to_string());

    let active_path = Some(
        core.app
            .session
            .active()
            .and_then(|d| d.path.clone())
            .unwrap_or_default(),
    );
    let has_unsaved_changes = core.app.session.documents().iter().any(|d| d.is_dirty());

    PlatformEffectsDto {
        cursor_icon,
        wants_ime,
        ime_rect,
        repaint_after_ms,
        copy_text,
        open_url,
        window_title,
        active_path,
        has_unsaved_changes,
        close_requested,
        picker_request,
        saved_file,
        print_job,
    }
}

fn duration_to_repaint_ms(delay: Duration) -> i64 {
    if delay.is_zero() {
        0
    } else if delay >= Duration::from_secs(3600) {
        -1
    } else {
        i64::try_from(delay.as_millis()).unwrap_or(1000).min(60_000)
    }
}

fn decode_modifiers(bits: i32) -> egui::Modifiers {
    egui::Modifiers {
        alt: (bits & MOD_ALT) != 0,
        ctrl: (bits & MOD_CTRL) != 0,
        shift: (bits & MOD_SHIFT) != 0,
        mac_cmd: (bits & MOD_MAC_CMD) != 0,
        command: (bits & MOD_COMMAND) != 0 || (bits & MOD_CTRL) != 0 || (bits & MOD_MAC_CMD) != 0,
    }
}

fn sync_modifiers_event(
    next: egui::Modifiers,
    applied: &mut egui::Modifiers,
    out: &mut Vec<egui::Event>,
) {
    if *applied != next {
        *applied = next;
        out.push(egui::Event::ModifiersChanged(next));
    }
}

fn decode_pointer_button(button: i32) -> egui::PointerButton {
    match button {
        1 => egui::PointerButton::Secondary,
        2 => egui::PointerButton::Middle,
        3 => egui::PointerButton::Extra1,
        4 => egui::PointerButton::Extra2,
        _ => egui::PointerButton::Primary,
    }
}

fn decode_touch_phase(phase: i32) -> egui::TouchPhase {
    match phase {
        0 => egui::TouchPhase::Start,
        1 => egui::TouchPhase::Move,
        2 => egui::TouchPhase::End,
        _ => egui::TouchPhase::Cancel,
    }
}

fn decode_egui_key(code: i32) -> Option<egui::Key> {
    use egui::Key;
    Some(match code {
        1 => Key::ArrowDown,
        2 => Key::ArrowLeft,
        3 => Key::ArrowRight,
        4 => Key::ArrowUp,
        5 => Key::Escape,
        6 => Key::Tab,
        7 => Key::Backspace,
        8 => Key::Enter,
        9 => Key::Space,
        10 => Key::Insert,
        11 => Key::Delete,
        12 => Key::Home,
        13 => Key::End,
        14 => Key::PageUp,
        15 => Key::PageDown,
        16 => Key::Copy,
        17 => Key::Cut,
        18 => Key::Paste,
        19 => Key::Minus,
        20 => Key::Plus,
        21 => Key::Equals,
        22 => Key::OpenBracket,
        23 => Key::CloseBracket,
        24 => Key::Backslash,
        25 => Key::Semicolon,
        26 => Key::Quote,
        27 => Key::Comma,
        28 => Key::Period,
        29 => Key::Slash,
        30 => Key::Backtick,
        31 => Key::Num0,
        32 => Key::Num1,
        33 => Key::Num2,
        34 => Key::Num3,
        35 => Key::Num4,
        36 => Key::Num5,
        37 => Key::Num6,
        38 => Key::Num7,
        39 => Key::Num8,
        40 => Key::Num9,
        41 => Key::A,
        42 => Key::B,
        43 => Key::C,
        44 => Key::D,
        45 => Key::E,
        46 => Key::F,
        47 => Key::G,
        48 => Key::H,
        49 => Key::I,
        50 => Key::J,
        51 => Key::K,
        52 => Key::L,
        53 => Key::M,
        54 => Key::N,
        55 => Key::O,
        56 => Key::P,
        57 => Key::Q,
        58 => Key::R,
        59 => Key::S,
        60 => Key::T,
        61 => Key::U,
        62 => Key::V,
        63 => Key::W,
        64 => Key::X,
        65 => Key::Y,
        66 => Key::Z,
        67 => Key::F1,
        68 => Key::F2,
        69 => Key::F3,
        70 => Key::F4,
        71 => Key::F5,
        72 => Key::F6,
        73 => Key::F7,
        74 => Key::F8,
        75 => Key::F9,
        76 => Key::F10,
        77 => Key::F11,
        78 => Key::F12,
        _ => return None,
    })
}

fn map_cursor_icon(icon: egui::CursorIcon) -> &'static str {
    use egui::CursorIcon as C;
    match icon {
        C::Default => "default",
        C::None => "none",
        C::ContextMenu => "context_menu",
        C::Help => "help",
        C::PointingHand => "pointing_hand",
        C::Progress => "progress",
        C::Wait => "wait",
        C::Cell => "cell",
        C::Crosshair => "crosshair",
        C::Text => "text",
        C::VerticalText => "vertical_text",
        C::Alias => "alias",
        C::Copy => "copy",
        C::Move => "move",
        C::NoDrop => "no_drop",
        C::NotAllowed => "not_allowed",
        C::Grab => "grab",
        C::Grabbing => "grabbing",
        C::AllScroll => "all_scroll",
        C::ResizeHorizontal => "resize_horizontal",
        C::ResizeNeSw => "resize_ne_sw",
        C::ResizeNwSe => "resize_nw_se",
        C::ResizeVertical => "resize_vertical",
        C::ResizeEast => "resize_east",
        C::ResizeSouthEast => "resize_south_east",
        C::ResizeSouth => "resize_south",
        C::ResizeSouthWest => "resize_south_west",
        C::ResizeWest => "resize_west",
        C::ResizeNorthWest => "resize_north_west",
        C::ResizeNorth => "resize_north",
        C::ResizeNorthEast => "resize_north_east",
        C::ResizeColumn => "resize_column",
        C::ResizeRow => "resize_row",
        C::ZoomIn => "zoom_in",
        C::ZoomOut => "zoom_out",
    }
}

fn translate_queued_input(
    item: QueuedInput,
    zoom_factor: f32,
    applied_modifiers: &mut egui::Modifiers,
    out: &mut Vec<egui::Event>,
) {
    let z = zoom_factor.max(0.1);
    match item {
        QueuedInput::PointerMoved {
            x_native_pt,
            y_native_pt,
        } => {
            out.push(egui::Event::PointerMoved(egui::Pos2::new(
                x_native_pt / z,
                y_native_pt / z,
            )));
        }
        QueuedInput::PointerGone => {
            out.push(egui::Event::PointerGone);
        }
        QueuedInput::PointerButton {
            x_native_pt,
            y_native_pt,
            button,
            pressed,
            modifiers,
        } => {
            let mods = decode_modifiers(modifiers);
            sync_modifiers_event(mods, applied_modifiers, out);
            out.push(egui::Event::PointerButton {
                pos: egui::Pos2::new(x_native_pt / z, y_native_pt / z),
                button: decode_pointer_button(button),
                pressed,
                modifiers: mods,
            });
        }
        QueuedInput::MouseWheel {
            delta_x,
            delta_y,
            is_line_unit,
            modifiers,
        } => {
            let mods = decode_modifiers(modifiers);
            sync_modifiers_event(mods, applied_modifiers, out);
            let unit = if is_line_unit {
                egui::MouseWheelUnit::Line
            } else {
                egui::MouseWheelUnit::Point
            };
            let delta = if is_line_unit {
                egui::Vec2::new(delta_x, delta_y)
            } else {
                egui::Vec2::new(delta_x / z, delta_y / z)
            };
            out.push(egui::Event::MouseWheel {
                unit,
                delta,
                phase: egui::TouchPhase::Move,
                modifiers: mods,
                source: egui::MouseWheelSource::Unknown,
            });
        }
        QueuedInput::Touch {
            device_id,
            pointer_id,
            phase,
            x_native_pt,
            y_native_pt,
            force,
        } => {
            out.push(egui::Event::Touch {
                device_id: egui::TouchDeviceId(device_id.max(0) as u64),
                id: egui::TouchId(pointer_id.max(0) as u64),
                phase: decode_touch_phase(phase),
                pos: egui::Pos2::new(x_native_pt / z, y_native_pt / z),
                force: (force.is_finite() && force > 0.0).then_some(force),
            });
        }
        QueuedInput::Zoom { delta } => {
            if delta.is_finite() && delta > 0.0 {
                out.push(egui::Event::Zoom(delta));
            }
        }
        QueuedInput::Key {
            key_code,
            pressed,
            repeat,
            modifiers,
        } => {
            if let Some(key) = decode_egui_key(key_code) {
                let mods = decode_modifiers(modifiers);
                sync_modifiers_event(mods, applied_modifiers, out);
                if pressed && !repeat {
                    let exact_cmd = mods.command && !mods.shift && !mods.alt;
                    if key == egui::Key::Copy || (exact_cmd && key == egui::Key::C) {
                        out.push(egui::Event::Copy);
                    } else if key == egui::Key::Cut || (exact_cmd && key == egui::Key::X) {
                        out.push(egui::Event::Cut);
                    } else if key == egui::Key::Paste || (exact_cmd && key == egui::Key::V) {
                        if let Some(text) = arboard::android_peek_clipboard_text()
                            && !text.is_empty()
                        {
                            out.push(egui::Event::Paste(text));
                        }
                    }
                }
                out.push(egui::Event::Key {
                    key,
                    physical_key: Some(key),
                    pressed,
                    repeat,
                    modifiers: mods,
                });
            }
        }
        QueuedInput::Text(text) => {
            if !text.is_empty() {
                out.push(egui::Event::Text(text));
            }
        }
    }
}

fn create_gles_painter() -> Result<egui_glow::Painter, String> {
    // SAFETY: Loading libEGL.so and libGLESv3.so via dlopen/dlsym on an active EGL thread
    // created by Android's GLSurfaceView (EGLContextClientVersion 3). Null pointers are checked
    // at every step before transmuting or invoking `eglGetProcAddress`.
    let gl = unsafe {
        let egl_handle = libc::dlopen(c"libEGL.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL);
        let gles_handle = {
            let h3 = libc::dlopen(c"libGLESv3.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL);
            if !h3.is_null() {
                h3
            } else {
                libc::dlopen(c"libGLESv2.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL)
            }
        };

        type EglGetProcAddressFn =
            unsafe extern "C" fn(*const libc::c_char) -> *const libc::c_void;
        let egl_get_proc: Option<EglGetProcAddressFn> = if !egl_handle.is_null() {
            let sym = libc::dlsym(egl_handle, c"eglGetProcAddress".as_ptr());
            if sym.is_null() {
                None
            } else {
                Some(std::mem::transmute::<
                    *mut libc::c_void,
                    EglGetProcAddressFn,
                >(sym))
            }
        } else {
            None
        };

        Arc::new(glow::Context::from_loader_function_cstr(|name: &CStr| {
            if let Some(get_proc) = egl_get_proc {
                let p = get_proc(name.as_ptr());
                if !p.is_null() {
                    return p;
                }
            }
            if !gles_handle.is_null() {
                let sym = libc::dlsym(gles_handle, name.as_ptr());
                if !sym.is_null() {
                    return sym as *const libc::c_void;
                }
            }
            std::ptr::null()
        }))
    };

    egui_glow::Painter::new(gl, "", None, false).map_err(|e| e.to_string())
}

fn jstring_to_rust(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn rust_to_jstring<'a>(env: &'a JNIEnv<'a>, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_createEngine(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    files_dir: JString<'_>,
    cache_dir: JString<'_>,
    initial_prefs_json: JString<'_>,
) -> jlong {
    let files = jstring_to_rust(&mut env, &files_dir).unwrap_or_default();
    let cache = jstring_to_rust(&mut env, &cache_dir).unwrap_or_default();
    let prefs = jstring_to_rust(&mut env, &initial_prefs_json);
    let entry = EngineEntry::new(&files, &cache, prefs.as_deref());
    let handle = entry.handle();
    ENGINES
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .insert(handle, entry);
    handle
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_destroyEngine(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    THREAD_CORES.with(|cell| {
        if let Some(mut core) = cell.borrow_mut().remove(&handle)
            && let Some(mut painter) = core.painter.take()
        {
            painter.destroy();
        }
    });
    ENGINES
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .remove(&handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_onSurfaceCreated(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if let Some(engine) = get_engine(handle) {
        engine.ensure_painter_on_gl_thread();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_onSurfaceChanged(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    _width_px: jint,
    _height_px: jint,
    _pixels_per_point: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.request_wake();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_onSurfaceDestroyed(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if let Some(engine) = get_engine(handle) {
        engine.destroy_painter_on_gl_thread();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_stepFrame(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    time_secs: jdouble,
    width_px: jint,
    height_px: jint,
    pixels_per_point: jfloat,
    dark_mode: jboolean,
    focused: jboolean,
) -> jstring {
    let effects = match get_engine(handle) {
        Some(engine) => engine.step_headless_or_gl(
            true,
            time_secs,
            width_px.max(1) as u32,
            height_px.max(1) as u32,
            pixels_per_point,
            dark_mode != JNI_FALSE,
            focused != JNI_FALSE,
        ),
        None => PlatformEffectsDto::default(),
    };
    let json_str = serde_json::to_string(&effects).unwrap_or_else(|_| "{}".to_string());
    rust_to_jstring(&env, &json_str)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_consumeWakeRequested(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jboolean {
    if get_engine(handle).is_some_and(|e| e.consume_wake()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushPointerMoved(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    x_pt: jfloat,
    y_pt: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::PointerMoved {
            x_native_pt: x_pt,
            y_native_pt: y_pt,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushPointerGone(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::PointerGone);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushPointerButton(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    x_pt: jfloat,
    y_pt: jfloat,
    button: jint,
    pressed: jboolean,
    modifiers: jint,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::PointerButton {
            x_native_pt: x_pt,
            y_native_pt: y_pt,
            button,
            pressed: pressed != JNI_FALSE,
            modifiers,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushMouseWheel(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    delta_x: jfloat,
    delta_y: jfloat,
    is_line_unit: jboolean,
    modifiers: jint,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::MouseWheel {
            delta_x,
            delta_y,
            is_line_unit: is_line_unit != JNI_FALSE,
            modifiers,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushTouch(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    device_id: jint,
    pointer_id: jint,
    phase: jint,
    x_pt: jfloat,
    y_pt: jfloat,
    force: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::Touch {
            device_id,
            pointer_id,
            phase,
            x_native_pt: x_pt,
            y_native_pt: y_pt,
            force,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushZoom(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    zoom_delta: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::Zoom { delta: zoom_delta });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushKey(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    egui_key_code: jint,
    pressed: jboolean,
    repeat: jboolean,
    modifiers: jint,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::Key {
            key_code: egui_key_code,
            pressed: pressed != JNI_FALSE,
            repeat: repeat != JNI_FALSE,
            modifiers,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_pushText(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    text: JString<'_>,
) {
    if let (Some(engine), Some(s)) = (get_engine(handle), jstring_to_rust(&mut env, &text)) {
        engine.enqueue_input(QueuedInput::Text(s));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_syncSystemClipboardText(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    text: JString<'_>,
) {
    if let Some(s) = jstring_to_rust(&mut env, &text) {
        arboard::android_set_clipboard_text(s);
    }
    if let Some(engine) = get_engine(handle) {
        engine.request_wake();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_deliverPickedFiles(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    request_id: jlong,
    paths: JObjectArray<'_>,
) {
    let mut out_paths = Vec::new();
    if !paths.is_null()
        && let Ok(len) = env.get_array_length(&paths)
    {
        for i in 0..len {
            if let Ok(elem) = env.get_object_array_element(&paths, i) {
                let js = JString::from(elem);
                if let Some(s) = jstring_to_rust(&mut env, &js)
                    && !s.trim().is_empty()
                {
                    out_paths.push(s);
                }
            }
        }
    }
    if request_id > 0
        && let Some(engine) = get_engine(handle)
    {
        engine.deliver_picked_files(request_id as u64, out_paths);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_openExternalFile(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    display_name: JString<'_>,
    local_path: JString<'_>,
) {
    let name = jstring_to_rust(&mut env, &display_name).unwrap_or_default();
    let path = jstring_to_rust(&mut env, &local_path).unwrap_or_default();
    if let Some(engine) = get_engine(handle) {
        engine.open_external_file(name, path);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_executeCommand(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    command_id: JString<'_>,
) {
    if let (Some(engine), Some(cmd)) = (get_engine(handle), jstring_to_rust(&mut env, &command_id))
    {
        engine.execute_command(cmd);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_setUiScaleOverride(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    scale: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.set_ui_scale_override(scale);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_deckcraft_bridge_JniDeckCraftNativeBridge_persistState(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    let json_str = get_engine(handle)
        .map(|e| e.persist_state())
        .unwrap_or_default();
    rust_to_jstring(&env, &json_str)
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_SERIAL: Mutex<()> = Mutex::new(());

    fn temp_test_dirs(tag: &str) -> (PathBuf, PathBuf) {
        let root = std::env::temp_dir().join(format!(
            "deckcraft_android_bridge_test_{tag}_{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&root);
        let files = root.join("files");
        let cache = root.join("cache");
        std::fs::create_dir_all(&files).unwrap();
        std::fs::create_dir_all(&cache).unwrap();
        (files, cache)
    }

    #[test]
    fn headless_engine_boots_renders_home_and_opens_command_palette() {
        let _guard = TEST_SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
        let (files, cache) = temp_test_dirs("boot");
        let engine = EngineEntry::new(
            files.to_str().unwrap(),
            cache.to_str().unwrap(),
            None,
        );

        let fx = engine.step_headless_or_gl(false, 0.0, 1920, 1200, 2.0, true, true);
        assert_eq!(fx.window_title, "DeckCraft");
        assert!(!fx.has_unsaved_changes);

        engine.execute_command("file.new".to_string());
        engine.execute_command("slide.new".to_string());
        let fx2 = engine.step_headless_or_gl(false, 0.016, 1920, 1200, 2.0, true, true);
        assert!(fx2.window_title.contains("Presentation1"));
        assert!(fx2.has_unsaved_changes);

        engine.execute_command("app.command_palette".to_string());
        let _ = engine.step_headless_or_gl(false, 0.032, 1920, 1200, 2.0, true, true);
        THREAD_CORES.with(|cell| {
            let map = cell.borrow();
            let core = map.get(&engine.handle()).unwrap();
            assert!(core.app.palette.is_some());
        });
    }

    #[test]
    fn save_picker_and_open_picker_roundtrip_emit_saf_events() {
        let _guard = TEST_SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
        let (files, cache) = temp_test_dirs("saf");
        let engine = EngineEntry::new(
            files.to_str().unwrap(),
            cache.to_str().unwrap(),
            None,
        );

        engine.execute_command("file.new".to_string());
        engine.execute_command("slide.new".to_string());
        engine.execute_command("file.save".to_string());
        let fx = engine.step_headless_or_gl(false, 0.0, 1920, 1200, 2.0, true, true);
        let req = fx.picker_request.expect("expected save_file picker request");
        assert_eq!(req.mode, "save_file");
        assert_eq!(
            req.suggested_name.as_deref(),
            Some("Presentation1.deckcraft")
        );

        let dest = files.join("MyDeck.deckcraft").to_string_lossy().into_owned();
        engine.deliver_picked_files(req.id as u64, vec![dest.clone()]);
        let fx_saved = engine.step_headless_or_gl(false, 0.016, 1920, 1200, 2.0, true, true);
        assert_eq!(
            fx_saved.saved_file.as_ref().map(|s| s.path.as_str()),
            Some(dest.as_str())
        );
        assert_eq!(fx_saved.active_path.as_deref(), Some(dest.as_str()));
        assert!(!fx_saved.has_unsaved_changes);

        // Edit and save again in-place -> emits saved_file for existing path
        engine.execute_command("slide.new".to_string());
        engine.execute_command("file.save".to_string());
        let fx_resaved = engine.step_headless_or_gl(false, 0.032, 1920, 1200, 2.0, true, true);
        assert_eq!(
            fx_resaved.saved_file.as_ref().map(|s| s.path.as_str()),
            Some(dest.as_str())
        );

        // Open dialog emits open_file picker request
        engine.execute_command("file.open".to_string());
        let fx_open = engine.step_headless_or_gl(false, 0.048, 1920, 1200, 2.0, true, true);
        let open_req = fx_open.picker_request.expect("expected open_file picker request");
        assert_eq!(open_req.mode, "open_file");
    }

    #[test]
    fn print_command_spools_pdf_and_emits_print_job() {
        let _guard = TEST_SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
        let (files, cache) = temp_test_dirs("print");
        let engine = EngineEntry::new(
            files.to_str().unwrap(),
            cache.to_str().unwrap(),
            None,
        );

        engine.execute_command("file.new".to_string());
        engine.execute_command("app.print".to_string());
        let fx = engine.step_headless_or_gl(false, 0.0, 1920, 1200, 2.0, true, true);
        let print_job = fx.print_job.expect("expected print job");
        assert!(print_job.job_name.ends_with(".pdf"));
        assert!(Path::new(&print_job.pdf_path).is_file());
    }
}
