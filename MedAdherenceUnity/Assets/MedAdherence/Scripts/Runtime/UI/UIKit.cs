using System;
using UnityEngine;
using UnityEngine.Events;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace MedAdherence.UI
{
    /// <summary>
    /// Builds uGUI widgets from code so the whole app runs from an empty scene with no prefabs or
    /// hand-wired inspector references. Sizes are in reference pixels of a 1080x1920 canvas.
    /// </summary>
    public static class UIKit
    {
        public static readonly Color Bg = Hex("F3F6F8");
        public static readonly Color Surface = Color.white;
        public static readonly Color Primary = Hex("0D4761");
        public static readonly Color Accent = Hex("1F8FB3");
        public static readonly Color Good = Hex("2EA043");
        public static readonly Color Warn = Hex("E09A1E");
        public static readonly Color Bad = Hex("D1453B");
        public static readonly Color Muted = Hex("6B7780");
        public static readonly Color Ink = Hex("1C2328");
        public static readonly Color Line = Hex("DDE3E8");

        static Font font;
        static Sprite rounded;

        public static Font Font
        {
            get
            {
                if (font != null) return font;
                // Unity 2022.2+ ships LegacyRuntime.ttf; older versions ship Arial.ttf.
                try { font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf"); } catch { }
                if (font == null) try { font = Resources.GetBuiltinResource<Font>("Arial.ttf"); } catch { }
                if (font == null) font = Font.CreateDynamicFontFromOSFont("Roboto", 32);
                return font;
            }
        }

        /// <summary>9-sliced rounded rectangle generated at runtime (no texture assets needed).</summary>
        public static Sprite Rounded
        {
            get
            {
                if (rounded != null) return rounded;
                const int size = 64, r = 24;
                var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Clamp };
                var px = new Color32[size * size];
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                    {
                        float cx = Mathf.Clamp(x + 0.5f, r, size - r), cy = Mathf.Clamp(y + 0.5f, r, size - r);
                        float d = Vector2.Distance(new Vector2(x + 0.5f, y + 0.5f), new Vector2(cx, cy));
                        byte a = (byte)(255 * Mathf.Clamp01(r - d + 0.5f));
                        px[y * size + x] = new Color32(255, 255, 255, a);
                    }
                tex.SetPixels32(px);
                tex.Apply();
                rounded = Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100, 0,
                    SpriteMeshType.FullRect, new Vector4(r, r, r, r));
                return rounded;
            }
        }

        public static Color Hex(string hex)
        {
            ColorUtility.TryParseHtmlString("#" + hex, out var c);
            return c;
        }

        // ------------------------------------------------------------------ structure

        public static Canvas CreateCanvas(Transform parent)
        {
            var go = new GameObject("Canvas", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
            go.transform.SetParent(parent, false);
            var canvas = go.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            var scaler = go.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1080, 1920);
            scaler.matchWidthOrHeight = 0f;

            if (UnityEngine.Object.FindObjectOfType<EventSystem>() == null)
            {
                var es = new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule));
                es.transform.SetParent(parent, false);
            }
            return canvas;
        }

        public static RectTransform Rect(Transform parent, string name = "Rect")
        {
            var go = new GameObject(name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            return (RectTransform)go.transform;
        }

        public static RectTransform Stretch(RectTransform rt, float left = 0, float right = 0, float top = 0, float bottom = 0)
        {
            rt.anchorMin = Vector2.zero;
            rt.anchorMax = Vector2.one;
            rt.offsetMin = new Vector2(left, bottom);
            rt.offsetMax = new Vector2(-right, -top);
            return rt;
        }

        public static Image Panel(Transform parent, Color color, bool roundedCorners = false, string name = "Panel")
        {
            var rt = Rect(parent, name);
            var img = rt.gameObject.AddComponent<Image>();
            img.color = color;
            if (roundedCorners) { img.sprite = Rounded; img.type = Image.Type.Sliced; }
            return img;
        }

        public static VerticalLayoutGroup VBox(Transform parent, float spacing = 16, int pad = 0, string name = "VBox")
        {
            var rt = Rect(parent, name);
            var v = rt.gameObject.AddComponent<VerticalLayoutGroup>();
            Configure(v, spacing, pad);
            return v;
        }

        public static HorizontalLayoutGroup HBox(Transform parent, float spacing = 16, int pad = 0, string name = "HBox")
        {
            var rt = Rect(parent, name);
            var h = rt.gameObject.AddComponent<HorizontalLayoutGroup>();
            Configure(h, spacing, pad);
            h.childForceExpandWidth = false;
            return h;
        }

        public static void Configure(HorizontalOrVerticalLayoutGroup g, float spacing, int pad)
        {
            g.spacing = spacing;
            g.padding = new RectOffset(pad, pad, pad, pad);
            g.childControlWidth = true;
            g.childControlHeight = true;
            g.childForceExpandWidth = true;
            g.childForceExpandHeight = false;
            g.childAlignment = TextAnchor.UpperLeft;
        }

        /// <summary>A white rounded card that stacks its children vertically.</summary>
        public static VerticalLayoutGroup Card(Transform parent, Color? color = null, int pad = 32, float spacing = 12)
        {
            var img = Panel(parent, color ?? Surface, true, "Card");
            var v = img.gameObject.AddComponent<VerticalLayoutGroup>();
            Configure(v, spacing, pad);
            return v;
        }

        /// <summary>Vertical scroll area filling <paramref name="parent"/>. Returns the content transform.</summary>
        public static RectTransform Scroll(Transform parent, int pad = 32, float spacing = 24)
        {
            var root = Stretch(Rect(parent, "Scroll"));
            root.gameObject.AddComponent<Image>().color = Color.clear; // lets empty space receive drags
            var sr = root.gameObject.AddComponent<ScrollRect>();
            sr.horizontal = false;
            sr.movementType = ScrollRect.MovementType.Clamped;
            sr.scrollSensitivity = 40;

            var viewport = Stretch(Rect(root, "Viewport"));
            viewport.gameObject.AddComponent<RectMask2D>();
            var content = Rect(viewport, "Content");
            content.anchorMin = new Vector2(0, 1);
            content.anchorMax = new Vector2(1, 1);
            content.pivot = new Vector2(0.5f, 1);
            content.offsetMin = content.offsetMax = Vector2.zero;
            var v = content.gameObject.AddComponent<VerticalLayoutGroup>();
            Configure(v, spacing, pad);
            v.padding.bottom = pad * 4;
            content.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;

            sr.viewport = viewport;
            sr.content = content;
            return content;
        }

        public static LayoutElement Size(Component c, float height = -1, float width = -1, float flexW = -1, float flexH = -1)
        {
            var le = c.GetComponent<LayoutElement>() ?? c.gameObject.AddComponent<LayoutElement>();
            if (height >= 0) { le.minHeight = height; le.preferredHeight = height; }
            if (width >= 0) { le.minWidth = width; le.preferredWidth = width; }
            if (flexW >= 0) le.flexibleWidth = flexW;
            if (flexH >= 0) le.flexibleHeight = flexH;
            return le;
        }

        public static void Spacer(Transform parent, float height) => Size(Rect(parent, "Spacer"), height);

        public static void Clear(Transform t)
        {
            for (int i = t.childCount - 1; i >= 0; i--) UnityEngine.Object.Destroy(t.GetChild(i).gameObject);
        }

        // ------------------------------------------------------------------ widgets

        public static Text Label(Transform parent, string text, int size = 40, Color? color = null,
                                 FontStyle style = FontStyle.Normal, TextAnchor align = TextAnchor.MiddleLeft)
        {
            var rt = Rect(parent, "Label");
            var t = rt.gameObject.AddComponent<Text>();
            t.font = Font;
            t.text = text;
            t.fontSize = size;
            t.color = color ?? Ink;
            t.fontStyle = style;
            t.alignment = align;
            t.horizontalOverflow = HorizontalWrapMode.Wrap;
            t.verticalOverflow = VerticalWrapMode.Overflow;
            t.supportRichText = true;
            t.raycastTarget = false;
            return t;
        }

        public static Button Button(Transform parent, string label, Color color, UnityAction onClick,
                                    float height = 120, int fontSize = 40, Color? textColor = null)
        {
            var img = Panel(parent, color, true, "Button: " + label);
            var b = img.gameObject.AddComponent<Button>();
            var colors = b.colors;
            colors.highlightedColor = new Color(0.95f, 0.95f, 0.95f);
            colors.pressedColor = new Color(0.8f, 0.8f, 0.8f);
            b.colors = colors;
            if (onClick != null) b.onClick.AddListener(onClick);
            var t = Label(img.transform, label, fontSize, textColor ?? Color.white, FontStyle.Bold, TextAnchor.MiddleCenter);
            Stretch(t.rectTransform, 16, 16, 0, 0);
            Size(img, height);
            return b;
        }

        /// <summary>Small selectable pill used for frequency and duration presets.</summary>
        public static Button Chip(Transform parent, string label, bool selected, UnityAction onClick)
        {
            var b = Button(parent, label, selected ? Primary : Line, onClick, 96, 34, selected ? Color.white : Ink);
            Size(b, 96, -1, 1);
            return b;
        }

        public static InputField Input(Transform parent, string placeholder, string value = "",
                                       InputField.ContentType type = InputField.ContentType.Standard,
                                       float height = 110, bool multiline = false)
        {
            var img = Panel(parent, Surface, true, "Input");
            var outline = img.gameObject.AddComponent<Outline>();
            outline.effectColor = Line;
            outline.effectDistance = new Vector2(2, -2);
            var field = img.gameObject.AddComponent<InputField>();

            var text = Label(img.transform, "", 40, Ink, FontStyle.Normal, multiline ? TextAnchor.UpperLeft : TextAnchor.MiddleLeft);
            Stretch(text.rectTransform, 28, 28, multiline ? 20 : 0, multiline ? 20 : 0);
            text.supportRichText = false;
            text.verticalOverflow = multiline ? VerticalWrapMode.Truncate : VerticalWrapMode.Overflow;
            var ph = Label(img.transform, placeholder, 40, Muted, FontStyle.Italic, text.alignment);
            Stretch(ph.rectTransform, 28, 28, multiline ? 20 : 0, multiline ? 20 : 0);

            field.textComponent = text;
            field.placeholder = ph;
            field.contentType = type;
            field.lineType = multiline ? InputField.LineType.MultiLineNewline : InputField.LineType.SingleLine;
            field.text = value ?? "";
            Size(img, height);
            return field;
        }

        /// <summary>Label above an input.</summary>
        public static InputField Field(Transform parent, string label, string placeholder, string value = "",
                                       InputField.ContentType type = InputField.ContentType.Standard)
        {
            Label(parent, label, 34, Muted, FontStyle.Bold);
            return Input(parent, placeholder, value, type);
        }

        public static Toggle Toggle(Transform parent, string label, bool value, UnityAction<bool> onChange)
        {
            var row = HBox(parent, 24);
            row.childAlignment = TextAnchor.MiddleLeft;
            Size(row, 96);
            var box = Panel(row.transform, Line, true, "Box");
            Size(box, 72, 72);
            var check = Panel(box.transform, Good, true, "Check");
            Stretch(check.rectTransform, 12, 12, 12, 12);
            var t = row.gameObject.AddComponent<Toggle>();
            t.targetGraphic = box;
            t.graphic = check;
            t.isOn = value;
            if (onChange != null) t.onValueChanged.AddListener(onChange);
            var l = Label(row.transform, label, 38);
            Size(l, -1, -1, 1);
            l.raycastTarget = true; // tapping the text toggles too
            return t;
        }

        /// <summary>Horizontal bar showing a percentage, coloured by the 80/50 adherence thresholds.</summary>
        public static void Bar(Transform parent, string label, float percent, string suffix = null)
        {
            var row = VBox(parent, 6);
            Label(row.transform, string.Format("{0}  <b>{1:0}%</b>{2}", label, percent, suffix ?? ""), 34);
            var track = Panel(row.transform, Line, true, "Track");
            Size(track, 28);
            var fill = Panel(track.transform, ColorFor(percent), true, "Fill");
            fill.rectTransform.anchorMin = Vector2.zero;
            fill.rectTransform.anchorMax = new Vector2(Mathf.Clamp01(percent / 100f), 1);
            fill.rectTransform.offsetMin = fill.rectTransform.offsetMax = Vector2.zero;
        }

        public static Color ColorFor(float percent) => percent >= 80 ? Good : percent >= 50 ? Warn : Bad;

        public static Image Badge(Transform parent, string text, Color color)
        {
            var img = Panel(parent, color, true, "Badge");
            var l = Label(img.transform, text, 30, Color.white, FontStyle.Bold, TextAnchor.MiddleCenter);
            Stretch(l.rectTransform, 12, 12, 0, 0);
            Size(img, 64, Mathf.Max(140, text.Length * 20 + 40));
            return img;
        }
    }
}
