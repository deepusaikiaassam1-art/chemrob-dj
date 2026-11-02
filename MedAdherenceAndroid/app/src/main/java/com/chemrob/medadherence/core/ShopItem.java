package com.chemrob.medadherence.core;

/**
 * One item in the accessories shop. Deliberately not a medicine: see {@link Shop} for why the
 * catalogue carries no drugs at all.
 *
 * <p>{@code url} is the plain product page. The affiliate tag is added at the moment the link is
 * opened ({@link Shop#affiliateUrl}), so the catalogue itself stays free of tracking parameters and
 * a build with no tag configured simply opens the ordinary page.
 */
public final class ShopItem {
    /** Stable identifier, used by tests and by the "why am I seeing this" note. */
    public final String id;
    /** Shown to the patient; translated through {@link I18n}. */
    public final String name;
    /** One line on what it is for; translated. */
    public final String blurb;
    public final Category category;
    /** Indicative price in rupees, shown as "about ₹x" because sellers change prices. */
    public final int approxRupees;
    /** Product page, without any affiliate parameter. */
    public final String url;
    /** Drawable resource name for the tile icon, resolved by the UI layer. */
    public final String icon;

    public enum Category {
        ORGANISE("Organise your doses"),
        REMEMBER("Remember your doses"),
        MEASURE("Measure and check"),
        PROTECT("Everyday protection");

        public final String label;
        Category(String label) { this.label = label; }
    }

    public ShopItem(String id, String name, String blurb, Category category,
                    int approxRupees, String url, String icon) {
        this.id = id;
        this.name = name;
        this.blurb = blurb;
        this.category = category;
        this.approxRupees = approxRupees;
        this.url = url;
        this.icon = icon;
    }
}
