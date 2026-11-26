package remates.ui;

import java.util.function.Predicate;

/**
 * Checks the matching rules of the search/filter panel ({@link FiltroTexto}). Plain {@code main}, no framework:
 * exits with 0 when every check passes and 1 otherwise.
 */
public final class FiltroTextoTest {
    private static int fallos;
    private static int total;

    public static void main(String[] args) {
        // text: case and accents ignored, every word must appear
        si("san jose", "SAN JOSÉ");
        si("SAN JOSÉ", "san jose");
        si("jose san", "SAN JOSÉ");             // word order does not matter
        si("cobro heredia", "Juzgado de Cobro de Heredia");
        no("cobro cartago", "Juzgado de Cobro de Heredia");
        si("limon", "LIMÓN");
        si("  toyota  ", "Toyota");
        no("toyota", null);

        // numbers: as shown, in Costa Rican format, plain
        si("20,500,000", 20500000.0);
        si("20.500.000", 20500000.0);
        si("20500000", 20500000.0);
        si("250,3", 250.3);
        si("250.30", 250.3);
        no("21.000.000", 20500000.0);

        // comparisons on numbers
        si(">20.000.000", 20500000.0);
        si(">20,000,000.00", 20500000.0);
        no(">20.500.000", 20500000.0);
        si(">=20.500.000", 20500000.0);
        si("<1500", 250.3);
        si("<= 250,30", 250.3);
        si("=20500000", 20500000.0);
        no(">20.000.000", "No indicado");        // text cells never match a numeric comparison
        no(">20.000.000", null);

        // comparisons on dd/mm/yyyy dates
        si("<=31/01/2026", "15/01/2026");
        no("<=31/01/2026", "02/03/2026");
        si(">1/2/2026", "02/03/2026");
        si("=15/01/2026", "15/01/2026");
        no(">01/01/2026", "No indicado");

        // "=" with text: exact value (ignoring case/accents)
        si("=san jose", "SAN JOSÉ");
        no("=san", "SAN JOSÉ");

        // operator with garbage operand matches nothing; operator alone is plain text
        no(">abc", "abc");
        si(">", "a > b");

        // blank = no filter
        check("blank", FiltroTexto.condicion("   ") == null);

        // range for numeric columns: bounds included, null = no limit, text cells never match
        check("rango sin cotas inactivo", FiltroTexto.rango(null, null) == null);
        check("rango [10,20] incluye 10", FiltroTexto.rango(10.0, 20.0).test(10.0));
        check("rango [10,20] incluye 20", FiltroTexto.rango(10.0, 20.0).test(20.0));
        check("rango [10,20] excluye 20.01", !FiltroTexto.rango(10.0, 20.0).test(20.01));
        check("rango [10,20] excluye 9.99", !FiltroTexto.rango(10.0, 20.0).test(9.99));
        check("rango [10,∞) incluye 1e9", FiltroTexto.rango(10.0, null).test(1e9));
        check("rango (-∞,20] incluye 0", FiltroTexto.rango(null, 20.0).test(0.0));
        check("rango excluye «No indicado»", !FiltroTexto.rango(10.0, 20.0).test("No indicado"));
        check("rango excluye null", !FiltroTexto.rango(null, 20.0).test(null));
        check("rango con mín > máx no incluye nada", !FiltroTexto.rango(20.0, 10.0).test(15.0));
        check("cota «20.000.000»", Double.valueOf(20_000_000).equals(FiltroTexto.parseNumero("20.000.000")));
        check("cota «20,000,000.00»", Double.valueOf(20_000_000).equals(FiltroTexto.parseNumero("20,000,000.00")));
        check("cota inválida «abc»", FiltroTexto.parseNumero("abc") == null);

        // minus terms: "-palabra" excludes; several allowed; combined with included words
        FiltroTexto.Terminos t = FiltroTexto.terminos("Cartago -Turrialba -paraíso -");
        check("terminos incluir [cartago]", t.incluir().equals(java.util.List.of("cartago")));
        check("terminos excluir [turrialba, paraiso] (lone «-» ignored)",
            t.excluir().equals(java.util.List.of("turrialba", "paraiso")));
        si("-toyota -kia", "Hyundai");
        no("-toyota -kia", "TOYOTA");
        no("-toyota -kia", "Kia");
        si("cartago -turrialba", "CARTAGO, OROSI");
        no("cartago -turrialba", "TURRIALBA, CARTAGO");
        no("cartago -turrialba", "HEREDIA");
        si("-indicado", "SAN JOSÉ");             // "-indicado" hides "No indicado" cells...
        no("-indicado", "No indicado");          // ...and keeps the rest
        check("solo «-» no filtra", FiltroTexto.condicion(" - ") == null);
        si(">5", 10.0);                           // comparisons are unaffected by minus handling
        no("-20.000.000", 20000000.0);            // a minus term over a number excludes it

        // plus terms: "+a +b" = at least one of them; plain words required; "-" excluded
        FiltroTexto.Terminos tp = FiltroTexto.terminos("terreno +Cartago +HEREDIA -turrialba +");
        check("terminos alguno [cartago, heredia] (lone «+» ignored)",
            tp.alguno().equals(java.util.List.of("cartago", "heredia")));
        check("terminos incluir [terreno]", tp.incluir().equals(java.util.List.of("terreno")));
        check("terminos excluir [turrialba]", tp.excluir().equals(java.util.List.of("turrialba")));
        si("+cartago +heredia", "CARTAGO");
        si("+cartago +heredia", "Heredia");
        no("+cartago +heredia", "ALAJUELA");
        si("+cartago +heredia -turrialba", "CARTAGO, OROSI");
        no("+cartago +heredia -turrialba", "TURRIALBA, CARTAGO");
        si("terreno +cartago +heredia", "terreno en HEREDIA");
        no("terreno +cartago +heredia", "casa en HEREDIA");          // "terreno" still required
        si("+toyota", "Toyota");                                       // a single "+" word = required
        check("solo «+» no filtra", FiltroTexto.condicion(" + ") == null);

        // checkbox list: the cell text (as shown) must be one of the values
        java.util.Set<String> visibles = java.util.Set.of("CARTAGO", "No indicado");
        check("enValores incluye CARTAGO", FiltroTexto.enValores(visibles).test("CARTAGO"));
        check("enValores incluye «No indicado»", FiltroTexto.enValores(visibles).test("No indicado"));
        check("enValores excluye HEREDIA", !FiltroTexto.enValores(visibles).test("HEREDIA"));
        check("enValores compara el número como se muestra",
            FiltroTexto.enValores(java.util.Set.of("1,500.00")).test(1500.0));

        // date range: bounds included, null = no limit, non-dates never match
        java.time.LocalDate ene1 = java.time.LocalDate.of(2026, 1, 1);
        java.time.LocalDate ene31 = java.time.LocalDate.of(2026, 1, 31);
        check("rango de fechas sin cotas inactivo", FiltroTexto.rangoFechas(null, null) == null);
        check("fechas [01/01,31/01] incluye 01/01/2026", FiltroTexto.rangoFechas(ene1, ene31).test("01/01/2026"));
        check("fechas [01/01,31/01] incluye 31/01/2026", FiltroTexto.rangoFechas(ene1, ene31).test("31/01/2026"));
        check("fechas [01/01,31/01] excluye 01/02/2026", !FiltroTexto.rangoFechas(ene1, ene31).test("01/02/2026"));
        check("fechas [01/01,31/01] excluye 31/12/2025", !FiltroTexto.rangoFechas(ene1, ene31).test("31/12/2025"));
        check("fechas desde 01/01 incluye 2027", FiltroTexto.rangoFechas(ene1, null).test("15/06/2027"));
        check("fechas hasta 31/01 incluye 2025", FiltroTexto.rangoFechas(null, ene31).test("15/06/2025"));
        check("fechas excluye «No indicado»", !FiltroTexto.rangoFechas(ene1, ene31).test("No indicado"));
        check("fechas excluye null", !FiltroTexto.rangoFechas(ene1, null).test(null));

        System.out.printf("%d comprobaciones, %d fallos%n", total, fallos);
        System.exit(fallos == 0 ? 0 : 1);
    }

    private static void si(String filtro, Object celda) {
        Predicate<Object> p = FiltroTexto.condicion(filtro);
        check("«" + filtro + "» debe encontrar " + celda, p != null && p.test(celda));
    }

    private static void no(String filtro, Object celda) {
        Predicate<Object> p = FiltroTexto.condicion(filtro);
        check("«" + filtro + "» NO debe encontrar " + celda, p != null && !p.test(celda));
    }

    private static void check(String descripcion, boolean ok) {
        total++;
        if (!ok) {
            fallos++;
            System.out.println("FALLA: " + descripcion);
        }
    }

    private FiltroTextoTest() {
    }
}
