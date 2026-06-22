package se.kootryne.foodscanner;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONException;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 42;

    private enum ScanMode {
        LOOKUP,
        ADD_PRODUCT
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> mainHandler.post(command);

    private ExecutorService cameraExecutor;
    private ExecutorService networkExecutor;
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private ProcessCameraProvider cameraProvider;
    private ProductRepository productRepository;
    private LocalProductStore localProductStore;
    private ActivityResultLauncher<Void> ingredientPhotoLauncher;

    private PreviewView previewView;
    private TextView statusText;
    private TextView productNameText;
    private TextView gtinText;
    private TextView ingredientsText;
    private TextView sourceText;
    private ImageView productImage;

    private volatile boolean scanLocked = false;
    private ScanMode scanMode = ScanMode.LOOKUP;
    private String pendingAddGtin;
    private String pendingAddName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraExecutor = Executors.newSingleThreadExecutor();
        networkExecutor = Executors.newFixedThreadPool(3);
        productRepository = new ProductRepository();
        localProductStore = new LocalProductStore(this);

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        ingredientPhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicturePreview(),
                bitmap -> {
                    if (bitmap == null) {
                        showStatus("Ingen bild togs. Testa igen.");
                        return;
                    }
                    runIngredientOcr(bitmap);
                });

        buildUi();

        if (hasCameraPermission()) {
            startLookupCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(0xFFF7F7F7);
        scrollView.addView(root);

        TextView title = makeField("FoodScanner", 32, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = makeField("Skanna en streckkod, eller lägg till egna ingredienser med OCR.", 15, false);
        subtitle.setTextColor(0xFF555555);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(0xFF111111);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)));

        statusText = makeField("Startar kameran...", 15, false);
        statusText.setPadding(0, dp(12), 0, dp(8));
        root.addView(statusText);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        root.addView(buttonRow);

        Button scanButton = new Button(this);
        scanButton.setText("Skanna igen");
        scanButton.setOnClickListener(v -> startLookupCamera());
        buttonRow.addView(scanButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button manualButton = new Button(this);
        manualButton.setText("Skriv GTIN");
        manualButton.setOnClickListener(v -> showManualGtinDialog());
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        manualParams.setMargins(dp(8), 0, 0, 0);
        buttonRow.addView(manualButton, manualParams);

        Button addButton = new Button(this);
        addButton.setText("Lägg till produkt");
        addButton.setOnClickListener(v -> startAddProductFlow());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        addParams.setMargins(0, dp(8), 0, 0);
        root.addView(addButton, addParams);

        productImage = new ImageView(this);
        productImage.setBackgroundColor(0xFFE0E0E0);
        productImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220));
        imageParams.setMargins(0, dp(18), 0, dp(12));
        root.addView(productImage, imageParams);

        productNameText = makeField("Ingen produkt skannad än", 22, true);
        root.addView(productNameText);

        gtinText = makeField("GTIN: -", 15, false);
        root.addView(gtinText);

        sourceText = makeField("Källa: -", 14, false);
        root.addView(sourceText);

        TextView ingredientsTitle = makeField("Ingredienser", 18, true);
        ingredientsTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(ingredientsTitle);

        ingredientsText = makeField("Skanna en vara först.", 16, false);
        ingredientsText.setLineSpacing(0, 1.08f);
        root.addView(ingredientsText);

        setContentView(scrollView);
    }

    private TextView makeField(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(0xFF111111);
        view.setPadding(0, dp(3), 0, dp(3));
        if (bold) {
            view.setTypeface(null, 1);
        }
        return view;
    }

    private void showManualGtinDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Ex: 7310865084703");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Skriv streckkod / GTIN")
                .setView(input)
                .setNegativeButton("Avbryt", null)
                .setPositiveButton("Sök", (dialog, which) -> {
                    String gtin = sanitizeGtin(input.getText().toString());
                    if (isValidGtinLength(gtin)) {
                        if (cameraProvider != null) cameraProvider.unbindAll();
                        scanMode = ScanMode.LOOKUP;
                        lookupProduct(gtin);
                    } else {
                        showStatus("GTIN måste vara 8, 12, 13 eller 14 siffror.");
                    }
                })
                .show();
    }

    private void startAddProductFlow() {
        pendingAddGtin = null;
        pendingAddName = null;
        scanMode = ScanMode.ADD_PRODUCT;
        startCamera("Skanna streckkoden på produkten du vill lägga till.");
    }

    private void showAddProductNameDialog(String gtin) {
        pendingAddGtin = gtin;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Ex: Tonfisk i vatten");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Produktnamn")
                .setMessage("GTIN: " + gtin)
                .setView(input)
                .setNegativeButton("Avbryt", (dialog, which) -> {
                    scanMode = ScanMode.LOOKUP;
                    showStatus("Avbrutet.");
                })
                .setPositiveButton("Ta bild", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "Egen produkt";
                    }
                    pendingAddName = name;
                    takeIngredientPhoto();
                })
                .show();
    }

    private void takeIngredientPhoto() {
        showStatus("Ta en tydlig, nära bild på ingredienslistan.");
        ingredientPhotoLauncher.launch(null);
    }

    private void runIngredientOcr(Bitmap bitmap) {
        showStatus("Läser ingredienslistan med OCR...");
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image)
                .addOnSuccessListener(result -> {
                    String extracted = extractIngredientsFromOcr(result.getText());
                    showIngredientsReviewDialog(extracted);
                })
                .addOnFailureListener(error -> {
                    showStatus("OCR misslyckades: " + error.getMessage());
                    showIngredientsReviewDialog("");
                });
    }

    private void showIngredientsReviewDialog(String extractedIngredients) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(extractedIngredients == null ? "" : extractedIngredients.trim());
        input.setHint("Korrigera OCR-texten här innan du sparar.");

        new AlertDialog.Builder(this)
                .setTitle("Kontrollera ingredienser")
                .setMessage("OCR kan läsa fel. Kolla igenom texten innan du sparar.")
                .setView(input)
                .setNegativeButton("Avbryt", (dialog, which) -> {
                    scanMode = ScanMode.LOOKUP;
                    showStatus("Avbrutet.");
                })
                .setPositiveButton("Spara", (dialog, which) -> {
                    String ingredients = input.getText().toString().trim();
                    saveLocalProduct(ingredients);
                })
                .show();
    }

    private void saveLocalProduct(String ingredients) {
        if (!isValidGtinLength(pendingAddGtin)) {
            showStatus("Kunde inte spara: GTIN saknas.");
            return;
        }

        Product product = new Product(
                emptyTo(pendingAddName, "Egen produkt"),
                pendingAddGtin,
                ingredients,
                "",
                "Egen produkt (OCR)"
        );

        try {
            localProductStore.saveProduct(product);
            scanMode = ScanMode.LOOKUP;
            showProduct(product, pendingAddGtin);
            showStatus("Sparad lokalt. Nästa gång visas dina egna ingredienser.");
        } catch (JSONException error) {
            showStatus("Kunde inte spara produkten: " + error.getMessage());
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLookupCamera();
        } else {
            showStatus("Kameratillstånd behövs för att skanna streckkoder och ta OCR-bilder.");
        }
    }

    private void startLookupCamera() {
        scanMode = ScanMode.LOOKUP;
        startCamera("Rikta kameran mot streckkoden.");
    }

    private void startCamera(String statusMessage) {
        scanLocked = false;
        productImage.setImageDrawable(null);
        showStatus(statusMessage);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (Exception error) {
                showStatus("Kunde inte starta kameran: " + error.getMessage());
            }
        }, mainExecutor);
    }

    private void bindCameraUseCases(ProcessCameraProvider provider) {
        provider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    @androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (scanLocked) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String rawValue = barcode.getRawValue();
                        String gtin = sanitizeGtin(rawValue == null ? "" : rawValue);
                        if (isValidGtinLength(gtin)) {
                            onGtinFound(gtin);
                            break;
                        }
                    }
                })
                .addOnFailureListener(error -> showStatus("Skanningen misslyckades: " + error.getMessage()))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onGtinFound(String gtin) {
        if (scanLocked) return;
        scanLocked = true;
        mainHandler.post(() -> {
            if (cameraProvider != null) cameraProvider.unbindAll();
            if (scanMode == ScanMode.ADD_PRODUCT) {
                showAddProductNameDialog(gtin);
            } else {
                lookupProduct(gtin);
            }
        });
    }

    private void lookupProduct(String gtin) {
        Product localProduct = localProductStore.getProduct(gtin);
        if (localProduct != null) {
            showProduct(localProduct, gtin);
            showStatus("Hittad i dina egna sparade produkter.");
            return;
        }

        showStatus("Söker efter " + gtin + "...");
        productNameText.setText("Söker produkt...");
        gtinText.setText("GTIN: " + gtin);
        sourceText.setText("Källa: söker...");
        ingredientsText.setText("Hämtar ingredienser...");
        productImage.setImageDrawable(null);

        networkExecutor.execute(() -> {
            try {
                Product product = productRepository.lookup(gtin);
                mainHandler.post(() -> showProduct(product, gtin));
            } catch (Exception error) {
                mainHandler.post(() -> showLookupError(gtin, error));
            }
        });
    }

    private void showProduct(Product product, String gtin) {
        if (product == null) {
            productNameText.setText("Ingen produkt hittades");
            gtinText.setText("GTIN: " + gtin);
            sourceText.setText("Källa: ingen träff");
            ingredientsText.setText("Jag hittade ingen produktdata för den här streckkoden. Tryck på Lägg till produkt för att spara den själv.");
            showStatus("Ingen träff. Du kan lägga till produkten själv med OCR.");
            return;
        }

        productNameText.setText(emptyTo(product.name, "Namnlös produkt"));
        gtinText.setText("GTIN: " + emptyTo(product.gtin, gtin));
        sourceText.setText("Källa: " + emptyTo(product.source, "okänd"));
        ingredientsText.setText(emptyTo(product.ingredients, "Inga ingredienser angivna i datakällan."));

        String source = product.source == null ? "" : product.source.toLowerCase(Locale.ROOT);
        if (source.contains("egen produkt")) {
            showStatus("Hittad i dina egna sparade produkter.");
        } else if (source.contains("open food facts")) {
            showStatus("Hittad via fallback. Du kan lägga till egna ingredienser med OCR om något saknas.");
        } else {
            showStatus("Produkt hittad.");
        }

        if (product.imageUrl != null && !product.imageUrl.trim().isEmpty()) {
            loadImage(product.imageUrl);
        } else {
            productImage.setImageDrawable(null);
        }
    }

    private void showLookupError(String gtin, Exception error) {
        productNameText.setText("Kunde inte hämta produkt");
        gtinText.setText("GTIN: " + gtin);
        sourceText.setText("Källa: fel");
        ingredientsText.setText(error.getMessage() == null ? "Okänt fel." : error.getMessage());
        showStatus("Något gick fel vid produktuppslag.");
    }

    private void loadImage(String imageUrl) {
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", "FoodScanner Android");
                try (InputStream inputStream = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    if (bitmap != null) mainHandler.post(() -> productImage.setImageBitmap(bitmap));
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void showStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String sanitizeGtin(String value) {
        if (value == null) return "";
        return value.replaceAll("\\D", "");
    }

    private static boolean isValidGtinLength(String gtin) {
        int length = gtin == null ? 0 : gtin.length();
        return length == 8 || length == 12 || length == 13 || length == 14;
    }

    private static String extractIngredientsFromOcr(String text) {
        if (text == null) return "";

        String cleaned = text
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.isEmpty()) {
            return "";
        }

        String lower = cleaned.toLowerCase(Locale.ROOT);
        int start = findFirstIndex(lower, "ingredienser", "ingredients", "innehåll");
        if (start < 0) {
            return cleaned;
        }

        String after = cleaned.substring(start);
        after = after.replaceFirst("(?i)^(ingredienser|ingredients|innehåll)[:： ]*", "").trim();
        String afterLower = after.toLowerCase(Locale.ROOT);

        int end = after.length();
        String[] stopWords = {
                "näringsvärde",
                "näringsinformation",
                "nutrition",
                "allergener",
                "allergy",
                "förvaring",
                "förvaras",
                "bäst före",
                "netto",
                "ursprung",
                "tillverkad",
                "producent",
                "distributör"
        };

        for (String stopWord : stopWords) {
            int stopIndex = afterLower.indexOf(stopWord);
            if (stopIndex > 0 && stopIndex < end) {
                end = stopIndex;
            }
        }

        return after.substring(0, end).trim();
    }

    private static int findFirstIndex(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (barcodeScanner != null) barcodeScanner.close();
        if (textRecognizer != null) textRecognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (networkExecutor != null) networkExecutor.shutdownNow();
    }
}
