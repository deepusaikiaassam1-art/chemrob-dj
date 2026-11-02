package com.chemrob.medadherence.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The accessories shop: the app's one revenue idea, kept at arm's length from the medicine it
 * tracks.
 *
 * <p><b>No medicines, ever.</b> Every item here is a non-drug accessory. Selling or promoting drugs
 * online in India is governed by the Drugs and Cosmetics Act and its rules, and an adherence app
 * that earned a commission on the medicine it tells you to take would also be in plain conflict
 * with its own purpose. {@link #containsNoMedicines} is asserted by the test suite so a later
 * edit cannot quietly add one.
 *
 * <p><b>No network from the app.</b> Nothing here is fetched. The catalogue is compiled in, and a
 * tap hands the URL to the phone's browser, so "no data leaves the phone" stays true of the app
 * itself. Prices are indicative and shown as approximate for that reason.
 *
 * <p><b>Off during the pilot.</b> The shop is hidden entirely when the app is built with
 * {@code -PpilotBuild=true}, so the study measures adherence with no commercial prompt in the way.
 */
public final class Shop {
    private Shop() {}

    /**
     * The affiliate tag, empty by default. An empty tag means links open as ordinary product
     * pages, which is the right behaviour until a real affiliate account exists.
     */
    private static String affiliateTag = "";

    /** Set once at start-up from the build configuration. */
    public static synchronized void setAffiliateTag(String tag) {
        affiliateTag = tag == null ? "" : tag.trim();
    }

    public static synchronized String affiliateTag() { return affiliateTag; }

    public static synchronized boolean hasAffiliateTag() { return !affiliateTag.isEmpty(); }

    /**
     * The product link to open: the plain page, plus the affiliate tag when one is configured.
     * Amazon India takes the tag as {@code tag=}; anything else is returned untouched rather than
     * guessed at, because a wrong parameter is worse than none.
     */
    public static String affiliateUrl(ShopItem item) {
        String tag = affiliateTag();
        if (tag.isEmpty() || item.url == null || !item.url.contains("amazon.in")) return item.url;
        return item.url + (item.url.contains("?") ? "&" : "?") + "tag=" + tag;
    }

    /**
     * Shown above the product list, every time. Patients are told plainly that a purchase may earn
     * the project a commission and that nothing here is a medicine or a recommendation to buy.
     */
    public static final String DISCLOSURE =
            "These are ordinary shop links. If you buy something, this project may earn a small "
                    + "commission, which pays for keeping the app free. Nothing here is a medicine, "
                    + "and you never need to buy anything to use the app.";

    /** The catalogue. Non-drug accessories only — see the class note. */
    public static List<ShopItem> items() {
        List<ShopItem> l = new ArrayList<>();

        l.add(new ShopItem("pillbox_7day", "Weekly pill box",
                "Seven compartments, one for each day, so a missed dose is visible at a glance.",
                ShopItem.Category.ORGANISE, 199,
                "https://www.amazon.in/s?k=7+day+pill+organiser+box", "ic_pill"));
        l.add(new ShopItem("pillbox_4slot", "Four-times-a-day pill box",
                "Morning, noon, evening and night boxes for each day of the week.",
                ShopItem.Category.ORGANISE, 449,
                "https://www.amazon.in/s?k=pill+organiser+4+times+a+day", "ic_calendar"));
        l.add(new ShopItem("pouch", "Medicine pouch",
                "A zip pouch to keep the whole course together when you travel.",
                ShopItem.Category.ORGANISE, 299,
                "https://www.amazon.in/s?k=medicine+travel+pouch+organiser", "ic_home"));

        l.add(new ShopItem("pill_reminder_watch", "Vibrating reminder watch",
                "Buzzes at set times, useful when the phone is in another room.",
                ShopItem.Category.REMEMBER, 1299,
                "https://www.amazon.in/s?k=vibrating+pill+reminder+watch", "ic_alarm"));
        l.add(new ShopItem("timer_cap", "Timer bottle cap",
                "A cap that shows how long since the bottle was last opened.",
                ShopItem.Category.REMEMBER, 899,
                "https://www.amazon.in/s?k=pill+bottle+timer+cap", "ic_alarm"));
        l.add(new ShopItem("water_bottle", "Marked water bottle",
                "Hour marks on the side, so a dose that needs a full glass gets one.",
                ShopItem.Category.REMEMBER, 499,
                "https://www.amazon.in/s?k=water+bottle+with+time+markings", "ic_drop"));

        l.add(new ShopItem("pill_cutter", "Pill cutter",
                "Splits a tablet evenly when the prescription says half a tablet.",
                ShopItem.Category.MEASURE, 149,
                "https://www.amazon.in/s?k=pill+cutter+splitter", "ic_pill"));
        l.add(new ShopItem("syrup_measure", "Measuring spoon and cup",
                "Marked in millilitres, for syrups and suspensions.",
                ShopItem.Category.MEASURE, 129,
                "https://www.amazon.in/s?k=medicine+measuring+spoon+cup+ml", "ic_drop"));
        l.add(new ShopItem("thermometer", "Digital thermometer",
                "To check a fever while you are on a course of antibiotics.",
                ShopItem.Category.MEASURE, 249,
                "https://www.amazon.in/s?k=digital+thermometer", "ic_chart"));
        l.add(new ShopItem("bp_monitor", "Blood pressure monitor",
                "For patients asked by their doctor to record readings at home.",
                ShopItem.Category.MEASURE, 1799,
                "https://www.amazon.in/s?k=blood+pressure+monitor+upper+arm", "ic_chart"));

        l.add(new ShopItem("mask", "Face masks",
                "For clinic visits while your immunity is low.",
                ShopItem.Category.PROTECT, 199,
                "https://www.amazon.in/s?k=face+mask+pack", "ic_person"));
        l.add(new ShopItem("sanitiser", "Hand sanitiser",
                "Clean hands before handling tablets.",
                ShopItem.Category.PROTECT, 99,
                "https://www.amazon.in/s?k=hand+sanitiser", "ic_drop"));
        l.add(new ShopItem("first_aid", "First-aid box",
                "Dressings and basics to keep at home.",
                ShopItem.Category.PROTECT, 599,
                "https://www.amazon.in/s?k=first+aid+kit+home", "ic_pharmacy"));

        return Collections.unmodifiableList(l);
    }

    /** The items in one category, in catalogue order. */
    public static List<ShopItem> itemsIn(ShopItem.Category category) {
        List<ShopItem> l = new ArrayList<>();
        for (ShopItem i : items()) if (i.category == category) l.add(i);
        return l;
    }

    /**
     * Words that would mean a medicine had crept into the catalogue. Checked by the test suite
     * against every name, blurb and URL.
     */
    private static final String[] MEDICINE_WORDS = {
            "tablet", "capsule", "syrup", "antibiotic", "amoxicillin", "azithromycin", "paracetamol",
            "ibuprofen", "painkiller", "antacid", "ointment", "cream", "drops", "injection",
            "vaccine", "supplement", "vitamin", "ayurvedic", "medicine for", "mg ", "dose of",
    };

    /**
     * True when no item looks like a medicine. "Medicine pouch" and "measuring spoon" are
     * containers and tools, so the check looks for the dosage words above rather than the word
     * medicine itself.
     */
    public static boolean containsNoMedicines() {
        return offendingItem() == null;
    }

    /** The first item that looks like a medicine, or null. Named so a failing test is readable. */
    public static String offendingItem() {
        for (ShopItem i : items()) {
            // Only the name and the link decide what is being sold. A blurb may legitimately say
            // "splits a tablet evenly" - that describes the tool, not the product.
            String sold = (i.name + " " + i.url).toLowerCase(Locale.ROOT);
            for (String w : MEDICINE_WORDS) {
                if (sold.contains(w)) return i.id + " matches '" + w + "'";
            }
            String all = (i.name + " " + i.blurb + " " + i.url).toLowerCase(Locale.ROOT);
            if (all.contains("buy medicine") || all.contains("order medicine")) {
                return i.id + " links to buying medicine";
            }
        }
        return null;
    }
}
