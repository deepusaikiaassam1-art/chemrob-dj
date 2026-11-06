package com.chemrob.medadherence.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.chemrob.medadherence.BuildConfig;
import com.chemrob.medadherence.core.Shop;
import com.chemrob.medadherence.core.ShopItem;

import java.util.List;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * The accessories shop: a separate window, opened from the Progress tab, never shown while a dose
 * is being recorded.
 *
 * <p>This is the one screen where the app is openly commercial, so it says so at the top rather
 * than in a settings page nobody reads: the disclosure banner is the first thing under the hero and
 * cannot be dismissed. It is also the loudest screen in the app - gradients, sticker tiles, big
 * type - which is where that style belongs, as opposed to the dose screens a patient reads while
 * deciding whether to swallow a tablet.
 *
 * <p>Nothing is fetched. The catalogue is compiled in ({@link Shop}) and a tap hands the URL to the
 * phone's browser, so the app itself still makes no network request and no data about the patient
 * leaves the phone.
 *
 * <p>When the APK is built for the pilot study ({@code -PpilotBuild=true}) the entry point is
 * hidden and this activity refuses to open, so trial participants never see it.
 */
public class ShopActivity extends Activity {

    @Override protected void onCreate(Bundle state) {
        Ui.apply(this);
        super.onCreate(state);

        if (!BuildConfig.SHOP) {   // pilot build: nothing to show
            finish();
            return;
        }
        Shop.setAffiliateTag(BuildConfig.AFFILIATE_TAG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.vbox(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, pad, pad, Ui.dp(this, 28));
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        hero(body);
        disclosure(body);
        for (ShopItem.Category category : ShopItem.Category.values()) section(body, category);
        footer(body);
    }

    /** Title panel: loud on purpose, and honest about what the screen is. */
    private void hero(LinearLayout body) {
        LinearLayout h = Ui.vbox(this);
        h.setBackground(Ui.rounded(this, Ui.PRIMARY, 24));
        int hp = Ui.dp(this, 20);
        h.setPadding(hp, hp, hp, hp);
        body.addView(h, Ui.matchWrap(this, 14));
        LinearLayout tags = Ui.row(h);
        tags.setGravity(Gravity.START);
        int chipTint = Color.argb(56, 255, 255, 255);
        Care.chip(tags, "Shop", chipTint, Ui.ON_PRIMARY);
        Care.chip(tags, "Affiliate links", chipTint, Ui.ON_PRIMARY);
        Ui.text(h, "Things that help you finish the course", 26, Ui.ON_PRIMARY, true)
                .setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView sub = Ui.text(h, "Pill boxes, reminders and measuring tools. No medicines.",
                16, Care.blend(Ui.ON_PRIMARY, Ui.PRIMARY, 0.18f), false);
        sub.setPadding(0, Ui.dp(this, 8), 0, 0);
    }

    /** The commercial disclosure. First thing under the hero, on every visit, not dismissible. */
    private void disclosure(LinearLayout body) {
        LinearLayout card = Care.panel(body);
        Care.chip(card, "Affiliate links", Care.State.DUE);
        Ui.text(card, "Why you are seeing this", 17, Ui.INK, true);
        Ui.text(card, Shop.DISCLOSURE, 15, Ui.MUTED, false)
                .setPadding(0, Ui.dp(this, 6), 0, 0);
        if (!Shop.hasAffiliateTag()) {
            Ui.text(card, "No affiliate account is set up in this build, so these open as "
                    + "ordinary shop pages.", 14, Ui.MUTED, false)
                    .setPadding(0, Ui.dp(this, 8), 0, 0);
        }
    }

    private void section(LinearLayout body, ShopItem.Category category) {
        List<ShopItem> items = Shop.itemsIn(category);
        if (items.isEmpty()) return;

        int accent = Ui.PRIMARY;
        Care.section(body, category.label);

        for (ShopItem item : items) tile(body, item, accent);
    }

    /** One product: icon chip, name, a tilted price sticker, what it is for, and the shop button. */
    private void tile(LinearLayout body, ShopItem item, int accent) {
        LinearLayout card = Care.panel(body);

        LinearLayout top = Ui.row(card);
        int iconRes = getResources().getIdentifier(item.icon, "drawable", getPackageName());
        if (iconRes != 0) {
            FrameLayout chip = Ui.iconCircle(this, iconRes, Care.wash(), accent, 52);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 12);
            top.addView(chip, lp);
        }
        LinearLayout words = Ui.vbox(this);
        top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(words, item.name, 20, Ui.INK, true);
        Ui.text(words, tf("about %s", "₹" + item.approxRupees), 16, accent, true)
                .setPadding(0, Ui.dp(this, 2), 0, 0);

        Ui.text(card, item.blurb, 16, Ui.MUTED, false)
                .setPadding(0, Ui.dp(this, 12), 0, 0);

        Ui.button(card, "See it in the shop", Ui.PRIMARY, v -> open(item));
    }

    /** Closing note: the shop is optional, and the app does not need it. */
    private void footer(LinearLayout body) {
        Ui.text(body, "Prices change, so these are rough. The app works the same whether or not "
                + "you buy anything.", 14, Ui.MUTED, false)
                .setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        Ui.button(body, "Back", Ui.SURFACE_VARIANT, v -> finish());
    }

    /** Hands the link to the phone's browser. The app makes no request of its own. */
    private void open(ShopItem item) {
        String url = Shop.affiliateUrl(item);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, t("No app on this phone can open shop links."),
                    Toast.LENGTH_LONG).show();
        }
    }
}
