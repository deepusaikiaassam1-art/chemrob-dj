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
        LinearLayout h = Max.hero(body, Max.violet(), Max.pink());
        LinearLayout tags = Ui.row(h);
        tags.setGravity(Gravity.START);
        Max.tag(tags, "Shop", Color.argb(60, 255, 255, 255), Color.WHITE);
        Max.tag(tags, "Affiliate links", Color.argb(60, 255, 255, 255), Color.WHITE);
        Max.display(h, "Things that help you finish the course", 30, Color.WHITE)
                .setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView sub = Ui.text(h, "Pill boxes, reminders and measuring tools. No medicines.",
                17, Color.argb(225, 255, 255, 255), false);
        sub.setPadding(0, Ui.dp(this, 8), 0, 0);
    }

    /** The commercial disclosure. First thing under the hero, on every visit, not dismissible. */
    private void disclosure(LinearLayout body) {
        LinearLayout card = Max.edgeCard(body, Max.amber());
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

        int accent = Max.accent(category.ordinal());
        Max.rule(body, accent, Max.lighten(accent, 0.55f));
        Ui.text(body, category.label, 22, Ui.INK, true)
                .setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));

        for (ShopItem item : items) tile(body, item, accent);
    }

    /** One product: icon chip, name, what it is for, approximate price, and the shop button. */
    private void tile(LinearLayout body, ShopItem item, int accent) {
        LinearLayout card = Max.edgeCard(body, accent);

        LinearLayout top = Ui.row(card);
        int iconRes = getResources().getIdentifier(item.icon, "drawable", getPackageName());
        if (iconRes != 0) {
            FrameLayout chip = Ui.iconCircle(this, iconRes,
                    Color.argb(Ui.dark ? 54 : 30, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    accent, 46);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 12);
            top.addView(chip, lp);
        }
        LinearLayout words = Ui.vbox(this);
        top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(words, item.name, 19, Ui.INK, true);
        Ui.text(words, tf("about %s", "₹" + item.approxRupees), 15, accent, true)
                .setPadding(0, Ui.dp(this, 2), 0, 0);

        Ui.text(card, item.blurb, 16, Ui.MUTED, false)
                .setPadding(0, Ui.dp(this, 10), 0, 0);

        Max.bigAction(card, "See it in the shop", accent, Max.lighten(accent, 0.3f),
                v -> open(item));
    }

    /** Closing note: the shop is optional, and the app does not need it. */
    private void footer(LinearLayout body) {
        Max.rule(body, Ui.LINE, Ui.LINE);
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
