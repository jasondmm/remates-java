package remates.sheets;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;

import remates.sincronizados.HojaSincronizada;
import remates.ui.ColumnasRemate;

/**
 * Access to the user's spreadsheet with their Google account (OAuth "Desktop app"; the browser opens the first time
 * to approve access, then the stored sign-in is reused). Only the {@code spreadsheets} scope is requested.
 */
public final class ClienteSheets implements FuenteSheets {

    public static final String TAB_TERRENOS = ColumnasRemate.HOJA_TERRENOS;
    public static final String TAB_VEHICULOS = ColumnasRemate.HOJA_VEHICULOS;

    /** Only what the app uses: values as shown, numbers, and the basic formatting of each cell. */
    private static final String CAMPOS_DATOS = "properties.title,sheets(properties(sheetId,title,"
        + "gridProperties.columnCount),"
        + "data(rowData(values(effectiveValue,formattedValue,userEnteredFormat(backgroundColor,"
        + "horizontalAlignment,textFormat(foregroundColor,fontSize,bold,italic,underline,strikethrough))))))";

    private static final JsonFactory JSON = GsonFactory.getDefaultInstance();

    private final Sheets servicio;
    private final String spreadsheetId;

    /** Everything loaded from the file. */
    public record Carga(String tituloArchivo, HojaSincronizada terrenos, HojaSincronizada vehiculos) {
    }

    private ClienteSheets(Sheets servicio, String spreadsheetId) {
        this.servicio = servicio;
        this.spreadsheetId = spreadsheetId;
    }

    /**
     * Signs in (opening the browser the first time; this call waits until the user approves) and returns a client
     * for the configured spreadsheet.
     */
    public static ClienteSheets conectar(ConfiguracionSheets cfg) throws IOException {
        // HTTPS with the JDK's trusted certificates (Google deprecated its own bundled certificate store,
        // GoogleNetHttpTransport.newTrustedTransport / GoogleUtils.getCertificateTrustStore)
        NetHttpTransport transporte = new NetHttpTransport();
        GoogleClientSecrets secretos;
        try (Reader r = Files.newBufferedReader(cfg.credenciales(), StandardCharsets.UTF_8)) {
            secretos = GoogleClientSecrets.load(JSON, r);
        }
        GoogleAuthorizationCodeFlow flujo = new GoogleAuthorizationCodeFlow.Builder(transporte, JSON, secretos,
            List.of(SheetsScopes.SPREADSHEETS))
            .setDataStoreFactory(new FileDataStoreFactory(cfg.tokens().toFile()))
            .setAccessType("offline")
            .build();
        Credential credencial = new AuthorizationCodeInstalledApp(flujo, new LocalServerReceiver.Builder().build())
            .authorize("usuario");
        Sheets servicio = new Sheets.Builder(transporte, JSON, credencial).setApplicationName("Remates").build();
        return new ClienteSheets(servicio, cfg.spreadsheetId());
    }

    /**
     * Loads both tabs with values and formatting. An empty tab gets its header row written first; columns the
     * sheet lacks get their header added at the end.
     *
     * @throws IllegalStateException when a tab is missing
     */
    @Override
    public Carga cargar() throws IOException {
        Spreadsheet estructura = servicio.spreadsheets().get(spreadsheetId)
            .setFields("properties.title,sheets.properties(sheetId,title)").execute();
        List<String> faltan = new ArrayList<>();
        for (String tab : List.of(TAB_TERRENOS, TAB_VEHICULOS)) {
            if (estructura.getSheets().stream().noneMatch(s -> tab.equals(s.getProperties().getTitle()))) {
                faltan.add(tab);
            }
        }
        if (!faltan.isEmpty()) {
            throw new IllegalStateException("La hoja «" + estructura.getProperties().getTitle()
                + "» no tiene las pestañas: " + String.join(", ", faltan));
        }
        Spreadsheet datos = servicio.spreadsheets().get(spreadsheetId)
            .setRanges(List.of(rango(TAB_TERRENOS), rango(TAB_VEHICULOS)))
            .setIncludeGridData(true)
            .setFields(CAMPOS_DATOS)
            .execute();
        LectorHoja.Lectura terrenos = LectorHoja.leer(pestana(datos, TAB_TERRENOS), ColumnasRemate.TERRENOS_HEADERS);
        LectorHoja.Lectura vehiculos = LectorHoja.leer(pestana(datos, TAB_VEHICULOS), ColumnasRemate.VEH_HEADERS);
        List<Request> inicio = new ArrayList<>(terrenos.inicializacion());
        inicio.addAll(vehiculos.inicializacion());
        if (!inicio.isEmpty()) {
            enviar(inicio);
        }
        return new Carga(datos.getProperties().getTitle(), terrenos.hoja(), vehiculos.hoja());
    }

    /** Applies the requests in order, in one call ({@code spreadsheets.batchUpdate}). */
    @Override
    public void enviar(List<Request> peticiones) throws IOException {
        if (peticiones.isEmpty()) {
            return;
        }
        servicio.spreadsheets()
            .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(peticiones))
            .execute();
    }

    /** True when the stored sign-in is no longer valid (revoked or expired): the user must sign in again. */
    public static boolean esSesionInvalida(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TokenResponseException tr && tr.getDetails() != null
                && "invalid_grant".equals(tr.getDetails().getError())) {
                return true;
            }
            if (t instanceof GoogleJsonResponseException g && g.getStatusCode() == 401) {
                return true;
            }
        }
        return false;
    }

    /** A readable message for errors from Google or the network. */
    public static String mensaje(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof GoogleJsonResponseException g) {
                int codigo = g.getStatusCode();
                String detalle = g.getDetails() != null ? g.getDetails().getMessage() : g.getMessage();
                return switch (codigo) {
                    case 403 -> "Sin permiso para la hoja (" + detalle + ")";
                    case 404 -> "No se encontró la hoja; revise el enlace";
                    case 429 -> "Google limitó las escrituras por un momento; se reintentará";
                    default -> "Google Sheets respondió " + codigo + ": " + detalle;
                };
            }
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                || t instanceof java.net.SocketTimeoutException) {
                return "Sin conexión con Google";
            }
            if (t instanceof IllegalStateException || t instanceof IllegalArgumentException) {
                return t.getMessage();
            }
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String rango(String tab) {
        return "'" + tab.replace("'", "''") + "'";
    }

    private static Sheet pestana(Spreadsheet s, String tab) {
        return s.getSheets().stream().filter(x -> tab.equals(x.getProperties().getTitle())).findFirst()
            .orElseThrow(() -> new IllegalStateException("Falta la pestaña «" + tab + "»"));
    }
}
