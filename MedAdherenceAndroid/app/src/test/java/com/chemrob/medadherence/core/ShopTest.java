package com.chemrob.medadherence.core;

import org.junit.After;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The accessories shop. The point of most of these is to stop a later edit quietly turning a
 * non-drug accessory list into a medicine shop, which is the thing the app must not become.
 */
public class ShopTest {

    @After public void clearTag() {
        Shop.setAffiliateTag("");   // the tag is static; leave it as the tests found it
    }

    @Test public void catalogueSellsNoMedicines() {
        assertNull("a medicine reached the catalogue: " + Shop.offendingItem(), Shop.offendingItem());
        assertTrue(Shop.containsNoMedicines());
    }

    @Test public void everyItemIsComplete() {
        for (ShopItem i : Shop.items()) {
            assertNotNull(i.id, i.id);
            assertFalse("empty name: " + i.id, i.name.trim().isEmpty());
            assertFalse("empty blurb: " + i.id, i.blurb.trim().isEmpty());
            assertNotNull("no category: " + i.id, i.category);
            assertTrue("price should be positive: " + i.id, i.approxRupees > 0);
            assertTrue("links must be https: " + i.id, i.url.startsWith("https://"));
            assertFalse("the catalogue must carry no tracking parameters: " + i.id,
                    i.url.contains("tag="));
        }
    }

    @Test public void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ShopItem i : Shop.items()) {
            assertTrue("duplicate id: " + i.id, seen.add(i.id));
        }
    }

    @Test public void everyCategoryHasItems() {
        for (ShopItem.Category c : ShopItem.Category.values()) {
            assertFalse("no items in " + c, Shop.itemsIn(c).isEmpty());
        }
        int total = 0;
        for (ShopItem.Category c : ShopItem.Category.values()) total += Shop.itemsIn(c).size();
        assertEquals("every item belongs to exactly one shown category", Shop.items().size(), total);
    }

    @Test public void noAffiliateTagMeansPlainLinks() {
        Shop.setAffiliateTag("");
        assertFalse(Shop.hasAffiliateTag());
        for (ShopItem i : Shop.items()) {
            assertEquals(i.url, Shop.affiliateUrl(i));
        }
    }

    @Test public void tagIsAddedWithTheRightSeparator() {
        Shop.setAffiliateTag("medadherence-21");
        assertTrue(Shop.hasAffiliateTag());

        ShopItem withQuery = new ShopItem("q", "n", "b", ShopItem.Category.ORGANISE, 100,
                "https://www.amazon.in/s?k=pill+box", "ic_pill");
        assertEquals("https://www.amazon.in/s?k=pill+box&tag=medadherence-21",
                Shop.affiliateUrl(withQuery));

        ShopItem noQuery = new ShopItem("p", "n", "b", ShopItem.Category.ORGANISE, 100,
                "https://www.amazon.in/dp/B000000000", "ic_pill");
        assertEquals("https://www.amazon.in/dp/B000000000?tag=medadherence-21",
                Shop.affiliateUrl(noQuery));
    }

    @Test public void unknownSellersAreLeftAlone() {
        // Guessing a parameter for a shop whose scheme we do not know would be worse than none.
        Shop.setAffiliateTag("medadherence-21");
        ShopItem other = new ShopItem("o", "n", "b", ShopItem.Category.ORGANISE, 100,
                "https://example.com/product/42", "ic_pill");
        assertEquals("https://example.com/product/42", Shop.affiliateUrl(other));
    }

    @Test public void tagIsTrimmedAndNullSafe() {
        Shop.setAffiliateTag("  spaced-21  ");
        assertEquals("spaced-21", Shop.affiliateTag());
        Shop.setAffiliateTag(null);
        assertEquals("", Shop.affiliateTag());
        assertFalse(Shop.hasAffiliateTag());
    }

    @Test public void disclosureSaysWhatMatters() {
        String d = Shop.DISCLOSURE.toLowerCase();
        assertTrue("must admit the commission", d.contains("commission"));
        assertTrue("must say these are not medicines", d.contains("medicine"));
        assertTrue("must say buying is optional", d.contains("never need to buy"));
    }

    @Test public void catalogueIsReadOnly() {
        List<ShopItem> items = Shop.items();
        try {
            items.add(items.get(0));
            fail("the catalogue should not be modifiable by callers");
        } catch (UnsupportedOperationException expected) {
            // as intended
        }
    }
}
