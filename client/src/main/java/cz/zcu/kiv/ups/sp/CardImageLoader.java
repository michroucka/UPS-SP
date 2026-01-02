package cz.zcu.kiv.ups.sp;

import javafx.scene.image.Image;
import java.util.HashMap;
import java.util.Map;
import cz.zcu.kiv.ups.sp.Logger;

/**
 * Loads card images from resources
 */
public class CardImageLoader {
    private static final Map<String, Image> imageCache = new HashMap<>();
    private static Image backImage;

    /**
     * Gets the image for a card
     * @param cardName card name in format "BARVA-HODNOTA" (e.g., "SRDCE-KRAL")
     * @return card image or null if not found
     */
    public static Image getCardImage(String cardName) {
        if (cardName == null || cardName.isEmpty()) {
            return getBackImage();
        }

        // Check cache first
        if (imageCache.containsKey(cardName)) {
            return imageCache.get(cardName);
        }

        // Load image
        String imagePath = "/cz/zcu/kiv/ups/sp/assets/" + cardName + ".png";
        try {
            Image image = new Image(CardImageLoader.class.getResourceAsStream(imagePath));
            imageCache.put(cardName, image);
            return image;
        } catch (Exception e) {
            Logger.error("Failed to load card image: " + imagePath);
            return getBackImage();
        }
    }

    /**
     * Gets the back of card image
     * @return back image
     */
    public static Image getBackImage() {
        if (backImage == null) {
            String imagePath = "/cz/zcu/kiv/ups/sp/assets/RUB.png";
            try {
                backImage = new Image(CardImageLoader.class.getResourceAsStream(imagePath));
            } catch (Exception e) {
                Logger.error("Failed to load card back image: " + imagePath);
            }
        }
        return backImage;
    }

    /**
     * Preloads all card images
     */
    public static void preloadImages() {
        String[] suits = {"SRDCE", "KULE", "LISTY", "ZALUDY"};
        String[] ranks = {"SEDM", "OSM", "DEVET", "DESET", "SPODEK", "SVRSEK", "KRAL", "ESO"};

        for (String suit : suits) {
            for (String rank : ranks) {
                getCardImage(suit + "-" + rank);
            }
        }

        getBackImage();
    }

    /**
     * Clears the image cache
     */
    public static void clearCache() {
        imageCache.clear();
        backImage = null;
    }
}
