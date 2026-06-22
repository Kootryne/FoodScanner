# FoodScanner

Android-app för Samsung Galaxy S10/Android som skannar EAN/UPC-streckkoder och visar produktnamn, bild och ingredienser.

## Vad appen gör

- Skannar streckkoder med kameran via CameraX + ML Kit.
- Slår upp GTIN/EAN-koden.
- Visar produktnamn, GTIN, bild och ingredienser.
- Låter dig lägga till en egen produkt genom att skanna streckkoden, skriva produktnamn och ta en bild på ingredienslistan.
- Kör OCR på ingrediensbilden och låter dig granska/korrigera texten innan produkten sparas lokalt på mobilen.
- Är byggd för APK via GitHub Actions.

## Lägg till egen produkt med OCR

Tryck på `Lägg till produkt` i appen.

Flödet är:

1. Skanna produktens streckkod.
2. Skriv produktnamnet.
3. Ta en tydlig, nära bild på ingredienslistan.
4. Appen kör OCR med ML Kit Text Recognition.
5. Kontrollera och korrigera texten.
6. Spara produkten lokalt.

Nästa gång samma GTIN skannas visas den sparade produkten först, innan appen testar externa datakällor.

## Viktigt om GS1 Sweden ProductSearch

GS1 Sweden ProductSearch är en databas med produkter och bilder lanserade för svensk marknad. GS1:s sida säger att man kan söka via produktens GTIN, som är kopplat till streckkoden, men deras dokumenterade API-flöden verkar kräva GS1/Validoo-avtal eller API-åtkomst.

Därför är appen byggd så här:

1. Den försöker först använda en konfigurerad GS1-endpoint/proxy.
2. Om ingen GS1-endpoint är konfigurerad faller den tillbaka till Open Food Facts så att APK:n ändå är testbar direkt.
3. Om du har sparat en egen produkt lokalt med OCR används den först.

För riktig GS1-data: skapa GitHub Actions secrets:

- `GS1_PRODUCT_ENDPOINT` – t.ex. `https://din-server.example.com/products/{gtin}` eller en GS1/Validoo-endpoint som tar `?gtin=`.
- `GS1_API_KEY` – valfri API-nyckel om din endpoint kräver det.

Appen skickar API-nyckeln som `Authorization: Bearer ...`, `Ocp-Apim-Subscription-Key` och `x-api-key` för att funka med vanliga API-gateways.

## Bygga APK

GitHub Actions bygger APK automatiskt på push.

Gå till:

`Actions -> Build Android APK -> senaste körningen -> Artifacts -> FoodScanner-debug-apk`

APK:n finns också lokalt om du kör:

```bash
gradle assembleDebug
```

Filen hamnar här:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Installera på S10

Ladda ner artifact-zippen från GitHub Actions, packa upp den och installera `app-debug.apk` på mobilen. Du kan behöva tillåta installation från okända källor.
