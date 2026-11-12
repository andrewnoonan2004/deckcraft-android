use super::{Align2, Icon, Rect, Sense, SlideApp, Tokens, Ui, icon_toggle, json, pos2, theme, vec2};

pub const TITLE_H: f32 = 38.0;

/// macOS draws the window into the title bar (`with_fullsize_content_view`) and AppKit paints the
/// traffic lights itself, 16 pt from the top of the window on macOS 27. Moving them would take
/// AppKit calls (`unsafe`, forbidden here), so on macOS the title bar is the band they are centred
/// in, with the controls beside them (#71). Up to macOS 15 the lights are smaller and centred 14 pt
/// from the top, about 2 pt above this bar's centre.
pub const MAC_TITLE_BAR: f32 = 32.0;

/// The traffic lights as AppKit lays them out, measured on macOS 27: button diameter,
/// centre-to-centre pitch and the first button's left edge.
pub(super) const MAC_LIGHT: f32 = 14.0;

pub(super) const MAC_LIGHT_PITCH: f32 = 23.0;

pub(super) const MAC_LIGHTS_LEFT: f32 = 9.0;

/// Where title-bar content starts on macOS: the end of the three lights plus the gap the lights
/// keep from the top of the window.
pub const MAC_CONTENT_LEFT: f32 = MAC_LIGHTS_LEFT + 2.0 * MAC_LIGHT_PITCH + MAC_LIGHT + (MAC_TITLE_BAR - MAC_LIGHT) / 2.0;

/// The title bar's height, the left edge of its content and the height of its tallest control.
/// With the integrated title bar (macOS) the bar is shorter, so the controls shrink to keep a
/// margin above and below them.
pub(super) fn title_geometry(integrated: bool) -> (f32, f32, f32) {
    if integrated { (MAC_TITLE_BAR, MAC_CONTENT_LEFT, 22.0) } else { (TITLE_H, 10.0, 26.0) }
}

/// The window's title row: traffic-light space, quick access, centred title, actions.
pub fn title_bar(app: &mut SlideApp, ui: &mut Ui) {
    let t = Tokens::get(ui.ctx());
    let (height, left, control_h) = title_geometry(app.integrated_titlebar);
    egui::Panel::top("title_bar").exact_size(height).frame(egui::Frame::NONE.fill(t.chrome)).show(ui, |ui| {
        let rect = ui.max_rect();
        // Dragging the empty title bar moves the window.
        let drag = ui.interact(rect, ui.scope_id().with("titledrag"), Sense::click_and_drag());
        // The drag surface is not a button: a full-width Button would be announced as one.
        drag.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Window, true, "Window title bar"));
        if drag.drag_started() {
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::StartDrag);
        }
        if drag.double_clicked() {
            let max = ui.input(|i| i.viewport().maximized.unwrap_or(false));
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::Maximized(!max));
        }
        let right_min_x = (rect.max.x - 320.0).max(rect.min.x + left + 80.0);
        let left_max_x = (rect.min.x + 420.0).min(rect.max.x - 190.0).max(rect.min.x + left + 40.0);
        let left_rect = Rect::from_min_max(pos2(rect.min.x + left, rect.min.y), pos2(left_max_x, rect.max.y));
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(left_rect).layout(egui::Layout::left_to_right(egui::Align::Center)));
        child.set_clip_rect(left_rect);
        child.spacing_mut().item_spacing.x = 2.0;
        let has_doc = app.session.active().is_some();
        let dirty = app.session.active().is_some_and(|d| d.is_dirty());
        crate::qat::show(app, &mut child);
        let left_end = child.min_rect().max.x;
        // Right side.
        let right_rect = Rect::from_min_max(pos2(right_min_x, rect.min.y), pos2(rect.max.x - 10.0, rect.max.y));
        let mut right = ui.new_child(egui::UiBuilder::new().max_rect(right_rect).layout(egui::Layout::right_to_left(egui::Align::Center)));
        right.set_clip_rect(right_rect);
        right.spacing_mut().item_spacing.x = 6.0;
        if icon_toggle(&mut right, Icon::Search, "Search commands (⇧⌘P)", true, false).clicked() {
            app.palette = Some((String::new(), 0));
        }
        let share = right.add(
            egui::Button::new(egui::RichText::new("  Present  ").color(t.accent_text).font(theme::bold(13.0)))
                .fill(t.accent)
                .corner_radius(control_h / 2.0)
                .min_size(vec2(84.0, control_h)),
        );
        if share.clicked() && has_doc {
            let from = app.session.active().map(|d| d.selection.slide).unwrap_or(0);
            app.start_show(from, false);
        }
        if icon_toggle(&mut right, Icon::CommentsBubble, "Comments", has_doc, app.ui.pane.as_deref() == Some("comments")).clicked() {
            let _ = app.run("view.pane", json!({"pane": "comments", "toggle": true}));
        }
        if icon_toggle(
            &mut right,
            if app.ui.brightness == theme::Brightness::Dark { Icon::Sparkle } else { Icon::Palette },
            "Light/Dark appearance",
            true,
            false,
        )
        .clicked()
        {
            let _ = app.run("view.dark", json!({}));
        }
        let right_start = right.min_rect().min.x;
        let gap_rect = Rect::from_min_max(pos2(left_end + 8.0, rect.min.y), pos2((right_start - 8.0).max(left_end + 8.0), rect.max.y));
        if gap_rect.width() >= 40.0 {
            let title =
                app.session.active().map(|d| format!("{}{}", d.title(), if dirty { " — Edited" } else { "" })).unwrap_or_else(|| "DeckCraft".into());
            let font = theme::font(13.0);
            let text_w = ui.fonts_mut(|f| f.layout_no_wrap(title.clone(), font.clone(), t.text).size().x);
            let center_x = if rect.center().x - text_w * 0.5 >= gap_rect.min.x && rect.center().x + text_w * 0.5 <= gap_rect.max.x {
                rect.center().x
            } else {
                gap_rect.center().x
            };
            ui.painter().with_clip_rect(gap_rect).text(pos2(center_x, rect.center().y), Align2::CENTER_CENTER, title, font, t.text);
        }
    });
}
