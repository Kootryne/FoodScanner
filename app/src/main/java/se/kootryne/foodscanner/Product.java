package se.kootryne.foodscanner;

final class Product {
    final String name;
    final String gtin;
    final String ingredients;
    final String imageUrl;
    final String source;

    Product(String name, String gtin, String ingredients, String imageUrl, String source) {
        this.name = name;
        this.gtin = gtin;
        this.ingredients = ingredients;
        this.imageUrl = imageUrl;
        this.source = source;
    }
}
