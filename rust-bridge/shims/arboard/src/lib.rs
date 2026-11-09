#![forbid(unsafe_code)]

use std::borrow::Cow;
use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, PoisonError};

#[derive(Debug)]
pub enum Error {
    ContentNotAvailable,
    ClipboardNotSupported,
    ClipboardOccupied,
    ConversionFailure,
    Unknown { description: String },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ContentNotAvailable => write!(f, "The clipboard contents were not available in the requested format or the clipboard is empty."),
            Self::ClipboardNotSupported => write!(f, "The native clipboard is not accessible due to being held by an other party."),
            Self::ClipboardOccupied => write!(f, "The native clipboard is occupied."),
            Self::ConversionFailure => write!(f, "The image or the text that was about the be transferred to/from the clipboard could not be converted to the appropriate format."),
            Self::Unknown { description } => write!(f, "Unknown error while interacting with the clipboard: {description}"),
        }
    }
}

impl std::error::Error for Error {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinuxClipboardKind {
    Clipboard,
    Primary,
    Secondary,
}

#[derive(Clone, Debug)]
pub struct ImageData<'a> {
    pub width: usize,
    pub height: usize,
    pub bytes: Cow<'a, [u8]>,
}

impl<'a> ImageData<'a> {
    pub fn to_owned_img(&self) -> ImageData<'static> {
        ImageData {
            width: self.width,
            height: self.height,
            bytes: Cow::Owned(self.bytes.as_ref().to_vec()),
        }
    }
}

#[derive(Default)]
struct SharedClipboardState {
    text: Option<String>,
    primary_text: Option<String>,
    pending_host_text: Option<String>,
    image: Option<(usize, usize, Vec<u8>)>,
    files: Vec<PathBuf>,
}

static STATE: Mutex<SharedClipboardState> = Mutex::new(SharedClipboardState {
    text: None,
    primary_text: None,
    pending_host_text: None,
    image: None,
    files: Vec::new(),
});

/// Update clipboard text from the Android host `ClipboardManager` without echoing it back as a new copy event.
pub fn android_set_clipboard_text(text: String) {
    let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
    if text.is_empty() {
        guard.text = None;
    } else {
        guard.text = Some(text);
    }
}

/// Drain newly copied text written via `arboard::Clipboard::set_text` so the Android host can sync it to `ClipboardManager`.
pub fn android_take_copied_text() -> Option<String> {
    let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
    guard.pending_host_text.take()
}

/// Read current clipboard text directly from the in-process bridge state.
pub fn android_peek_clipboard_text() -> Option<String> {
    let guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
    guard.text.clone()
}

pub struct Clipboard {
    _private: (),
}

impl Clipboard {
    pub fn new() -> Result<Self, Error> {
        Ok(Self { _private: () })
    }

    pub fn get_text(&mut self) -> Result<String, Error> {
        let guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        guard.text.clone().ok_or(Error::ContentNotAvailable)
    }

    pub fn set_text<'a, T: Into<Cow<'a, str>>>(&mut self, text: T) -> Result<(), Error> {
        let owned = text.into().into_owned();
        let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        guard.text = Some(owned.clone());
        guard.pending_host_text = Some(owned);
        Ok(())
    }

    pub fn get_html(&mut self) -> Result<String, Error> {
        Err(Error::ContentNotAvailable)
    }

    pub fn set_html<'a, T: Into<Cow<'a, str>>>(
        &mut self,
        html: T,
        alt_text: Option<T>,
    ) -> Result<(), Error> {
        if let Some(alt) = alt_text {
            self.set_text(alt)
        } else {
            self.set_text(html)
        }
    }

    pub fn get_image(&mut self) -> Result<ImageData<'static>, Error> {
        let guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        let (width, height, bytes) = guard.image.clone().ok_or(Error::ContentNotAvailable)?;
        Ok(ImageData {
            width,
            height,
            bytes: Cow::Owned(bytes),
        })
    }

    pub fn set_image(&mut self, image: ImageData<'_>) -> Result<(), Error> {
        let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        guard.image = Some((image.width, image.height, image.bytes.into_owned()));
        guard.text = None;
        Ok(())
    }

    pub fn clear(&mut self) -> Result<(), Error> {
        let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        guard.text = None;
        guard.primary_text = None;
        guard.pending_host_text = None;
        guard.image = None;
        guard.files.clear();
        Ok(())
    }

    pub fn get(&mut self) -> Get<'_> {
        Get {
            inner: self,
            kind: LinuxClipboardKind::Clipboard,
        }
    }

    pub fn set(&mut self) -> Set<'_> {
        Set {
            inner: self,
            kind: LinuxClipboardKind::Clipboard,
        }
    }
}

pub struct Get<'clipboard> {
    inner: &'clipboard mut Clipboard,
    kind: LinuxClipboardKind,
}

impl Get<'_> {
    pub fn text(self) -> Result<String, Error> {
        match self.kind {
            LinuxClipboardKind::Primary => {
                let guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
                guard.primary_text.clone().ok_or(Error::ContentNotAvailable)
            }
            _ => self.inner.get_text(),
        }
    }

    pub fn image(self) -> Result<ImageData<'static>, Error> {
        self.inner.get_image()
    }

    pub fn file_list(self) -> Result<Vec<PathBuf>, Error> {
        let guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        if guard.files.is_empty() {
            Err(Error::ContentNotAvailable)
        } else {
            Ok(guard.files.clone())
        }
    }
}

pub trait GetExtLinux {
    fn clipboard(self, selection: LinuxClipboardKind) -> Self;
}

impl GetExtLinux for Get<'_> {
    fn clipboard(mut self, selection: LinuxClipboardKind) -> Self {
        self.kind = selection;
        self
    }
}

pub struct Set<'clipboard> {
    inner: &'clipboard mut Clipboard,
    kind: LinuxClipboardKind,
}

impl Set<'_> {
    pub fn text<'a, T: Into<Cow<'a, str>>>(self, text: T) -> Result<(), Error> {
        match self.kind {
            LinuxClipboardKind::Primary => {
                let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
                guard.primary_text = Some(text.into().into_owned());
                Ok(())
            }
            _ => self.inner.set_text(text),
        }
    }

    pub fn html<'a, T: Into<Cow<'a, str>>>(self, html: T, alt_text: Option<T>) -> Result<(), Error> {
        self.inner.set_html(html, alt_text)
    }

    pub fn image(self, image: ImageData<'_>) -> Result<(), Error> {
        self.inner.set_image(image)
    }

    pub fn file_list(self, paths: &[impl AsRef<Path>]) -> Result<(), Error> {
        let mut guard = STATE.lock().unwrap_or_else(PoisonError::into_inner);
        guard.files = paths.iter().map(|p| p.as_ref().to_path_buf()).collect();
        Ok(())
    }
}

pub trait SetExtLinux {
    fn clipboard(self, selection: LinuxClipboardKind) -> Self;
    fn wait(self) -> Self;
}

impl SetExtLinux for Set<'_> {
    fn clipboard(mut self, selection: LinuxClipboardKind) -> Self {
        self.kind = selection;
        self
    }

    fn wait(self) -> Self {
        self
    }
}
