import re

UNITS = {
    'cero':0,'un':1,'uno':1,'una':1,'dos':2,'tres':3,'cuatro':4,'cinco':5,'seis':6,'siete':7,'ocho':8,'nueve':9,
    'diez':10,'once':11,'doce':12,'trece':13,'catorce':14,'quince':15,
    'dieciseis':16,'dieciséis':16,'diecisiete':17,'dieciocho':18,'diecinueve':19,
    'veinte':20,'veintiuno':21,'veintiun':21,'veintiún':21,'veintiuna':21,'veintidos':22,'veintidós':22,
    'veintitres':23,'veintitrés':23,'veinticuatro':24,'veinticinco':25,'veintiseis':26,'veintiséis':26,
    'veintisiete':27,'veintiocho':28,'veintinueve':29,
}
TENS = {'treinta':30,'cuarenta':40,'cincuenta':50,'sesenta':60,'setenta':70,'ochenta':80,'noventa':90}
HUNDREDS = {'cien':100,'ciento':100,'doscientos':200,'doscientas':200,'trescientos':300,'trescientas':300,
            'cuatrocientos':400,'cuatrocientas':400,'quinientos':500,'quinientas':500,'seiscientos':600,'seiscientas':600,
            'setecientos':700,'setecientas':700,'ochocientos':800,'ochocientas':800,'novecientos':900,'novecientas':900}

HUNDRED_WORDS = list(HUNDREDS.keys())
TENS_UNITS_WORDS = list(TENS.keys()) + list(UNITS.keys())
CONCAT_FIX_RE = re.compile(
    r'\b(' + '|'.join(sorted(HUNDRED_WORDS, key=len, reverse=True)) + r')(' +
    '|'.join(sorted(TENS_UNITS_WORDS, key=len, reverse=True)) + r')\b', re.IGNORECASE)

MIL_PEGADO_RE = re.compile(
    r'\bmil(?=(?:' + '|'.join(sorted(HUNDRED_WORDS + TENS_UNITS_WORDS, key=len, reverse=True)) + r')\b)', re.IGNORECASE)

def words_to_int(phrase):
    if phrase is None:
        return None
    s = phrase.lower().strip()
    s = re.sub(r'[()]', ' ', s)    # "(treinta" -> " treinta"
    s = re.sub(r'[.,]', '', s)
    s = CONCAT_FIX_RE.sub(r'\1 \2', s)
    s = MIL_PEGADO_RE.sub('mil ', s)   # "milcuatrocientos" -> "mil cuatrocientos"
    tokens = re.split(r'\s+', s)
    tokens = [t for t in tokens if t not in ('de',)]
    total = 0
    current = 0
    i = 0
    found_any = False
    while i < len(tokens):
        t = tokens[i]
        if t == 'y':
            i += 1
            continue
        if t in ('mil',):
            current = current if current else 1
            total += current * 1000
            current = 0
            found_any = True
        elif t in ('millon','millón','millones'):
            if total < 1_000_000:
                if not current and not total:
                    current = 1
                # "mil ochenta y cuatro millones" = (1000 + 84) millones
                total = (total + current) * 1_000_000
            else:
                total += (current or 1) * 1_000_000
            current = 0
            found_any = True
        elif t in HUNDREDS:
            current += HUNDREDS[t]
            found_any = True
        elif t in TENS:
            current += TENS[t]
            found_any = True
        elif t in UNITS:
            current += UNITS[t]
            found_any = True
        else:
            pass
        i += 1
    total += current
    return total if found_any else None

def format_thousands(n):
    return f"{n:,}".replace(',', '.')

CURRENCY_SYMBOL = {'colones':'₡','colon':'₡','colón':'₡','dolares':'$','dólares':'$','dolar':'$','dólar':'$'}

def parse_amount(text):
    if not text:
        return None
    s = text.strip()
    m_cur = re.search(r'(colones|colon(?:es)?|colón|dólares|dolares|dólar|dolar)', s, re.IGNORECASE)
    if not m_cur:
        return text
    symbol = CURRENCY_SYMBOL.get(m_cur.group(1).lower(), '')
    main_words = s[:m_cur.start()].strip()
    rest = s[m_cur.end():].strip()
    main_val = words_to_int(main_words)
    cents = 0
    m_cents = re.search(r'con\s+(.*?)\s*(?:c[eé]ntimos|centavos)', rest, re.IGNORECASE)
    if m_cents:
        cents_val = words_to_int(m_cents.group(1))
        cents = cents_val if cents_val is not None else 0
    if main_val is None:
        return text
    return f"{symbol}{format_thousands(main_val)},{cents:02d}"

def normalize_amount_symbol(s):
    if not s:
        return s
    return s.replace('¢', '₡')

def parse_amount_numeric(text):
    """Return (symbol, float_value) or (None, None) if unparseable."""
    if not text:
        return None, None
    s = text.strip()
    m_us = re.match(r'([¢₡$])\s*([\d,]+)\.(\d{2})\b', s)
    if m_us:
        symbol = '₡' if m_us.group(1) == '¢' else m_us.group(1)
        int_part = m_us.group(2).replace(',', '')
        try:
            return symbol, float(f"{int_part}.{m_us.group(3)}")
        except ValueError:
            pass
    # Cifra con formato es-CR al inicio ("¢38.788.454,88 (treinta y ocho millones...)"): la cifra manda
    # sobre el monto en letras que la sigue. Miles separados por punto o por espacio ("¢ 4 660 000").
    m_es = re.match(r'([¢₡$])\s*(\d{1,3}(?:\.\d{3})+|\d{1,3}(?:[ \u00a0]\d{3})+|\d+)(?:,(\d{2}))?(?![\d.,]*\d)', s)
    if m_es:
        symbol = '₡' if m_es.group(1) == '¢' else m_es.group(1)
        entero = re.sub(r'[. \u00a0]', '', m_es.group(2))
        return symbol, float(f"{entero}.{m_es.group(3) or '00'}")
    m_cur = re.search(r'(colones|colon(?:es)?|colón|d[oó]lares?\s*(?:estadounidenses|americanos)?|dolar|dólar)', s, re.IGNORECASE)
    if m_cur:
        symbol = '$' if 'dolar' in m_cur.group(1).lower() or 'dólar' in m_cur.group(1).lower() else CURRENCY_SYMBOL.get(m_cur.group(1).lower(), '')
        main_words = s[:m_cur.start()].strip()
        rest = s[m_cur.end():].strip()
        m_num = re.match(r'^[¢₡$]?\s*([\d,]+)\.(\d{2})$', main_words)
        if m_num:
            try:
                return symbol, float(f"{m_num.group(1).replace(',', '')}.{m_num.group(2)}")
            except ValueError:
                pass
        m_num2 = re.match(r'^[¢₡$]?\s*(\d{1,3}(?:\.\d{3})+)$', main_words)
        if m_num2:
            try:
                return symbol, float(m_num2.group(1).replace('.', ''))
            except ValueError:
                pass
        main_val = words_to_int(main_words)
        cents = 0
        m_cents = re.search(r'con\s+(.*?)\s*(?:c[eé]ntimos|centavos)', rest, re.IGNORECASE)
        if m_cents:
            cents_val = words_to_int(m_cents.group(1))
            cents = cents_val if cents_val is not None else 0
        if main_val is None:
            return None, None
        return symbol, round(main_val + cents/100, 2)
    m2 = re.match(r'([¢₡$])\s*([\d\.]+)(?:,(\d{2}))?', s)
    if m2:
        symbol = '₡' if m2.group(1) == '¢' else m2.group(1)
        int_part = m2.group(2).replace('.', '')
        dec_part = m2.group(3) or '00'
        try:
            val = float(f"{int_part}.{dec_part}")
            return symbol, val
        except ValueError:
            return None, None
    val = words_to_int(s)
    if val is not None:
        return None, float(val)
    return None, None

def parse_area(text):
    if not text:
        return text
    s = text.strip()
    m = re.search(r'(.*?)\s*METROS\s*(?:CON\s+(.*?)\s*DEC[IÍ]MET+ROS)?\s*(?:CUADRADOS?)?', s, re.IGNORECASE)
    if not m:
        return text
    main_words = m.group(1)
    dec_words = m.group(2)
    main_val = words_to_int(main_words)
    if main_val is None:
        return text
    if dec_words:
        dec_val = words_to_int(dec_words)
        dec_val = dec_val if dec_val is not None else 0
        return f"{format_thousands(main_val)},{dec_val:02d} m²"
    return f"{format_thousands(main_val)} m²"

def numero_cifras(s):
    """Número escrito en cifras: '1.500' -> 1500, '1.500,25' -> 1500.25, '1,500.25' -> 1500.25, '250.30' -> 250.3."""
    if re.fullmatch(r'\d{1,3}(?:\.\d{3})+(?:,\d+)?', s):      # miles con punto, decimales con coma
        return float(s.replace('.', '').replace(',', '.'))
    if re.fullmatch(r'\d{1,3}(?:,\d{3})+(?:\.\d+)?', s):      # miles con coma, decimales con punto
        return float(s.replace(',', ''))
    if re.fullmatch(r'\d+(?:[.,]\d+)?', s):                    # sin miles; punto o coma decimal
        return float(s.replace(',', '.'))
    return None

def parse_area_numeric(text):
    """Parse area phrase to a plain float (square meters), or None."""
    if not text:
        return None
    s = text.strip()
    m = re.search(r'(.*?)\s*METROS\s*(?:CON\s+(.*?)\s*DEC[IÍ]MET+ROS)?\s*(?:CUADRADOS?)?', s, re.IGNORECASE)
    if not m:
        return None
    main_words = m.group(1).strip()
    dec_words = m.group(2)
    if re.fullmatch(r'[\d.,]+', main_words):
        main_val = numero_cifras(main_words)
    else:
        main_val = words_to_int(main_words)
    if main_val is None:
        return None
    dec_val = 0
    if dec_words:
        dec_words = dec_words.strip()
        if re.fullmatch(r'[\d.,]+', dec_words):
            dec_val = int(re.sub(r'[.,]', '', dec_words))
        else:
            dv = words_to_int(dec_words)
            dec_val = dv if dv is not None else 0
    return round(main_val + dec_val/100, 2)

def parse_matricula_num(text):
    if not text:
        return text
    s = text.strip()
    if re.search(r'[a-zA-Z]', s) and not re.search(r'\d', s):
        val = words_to_int(s)
        return str(val) if val is not None else s
    return s
