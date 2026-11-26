import re, json, sys
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
from es_numbers import (parse_amount, parse_area, parse_matricula_num, words_to_int,
                         normalize_amount_symbol, parse_amount_numeric, parse_area_numeric)

MESES = {
    'enero':1,'febrero':2,'marzo':3,'abril':4,'mayo':5,'junio':6,'julio':7,
    'agosto':8,'setiembre':9,'septiembre':9,'octubre':10,'noviembre':11,'diciembre':12
}
UNIDADES = {'cero':0,'uno':1,'un':1,'una':1,'dos':2,'tres':3,'cuatro':4,'cinco':5,'seis':6,'siete':7,'ocho':8,'nueve':9,
            'diez':10,'once':11,'doce':12,'trece':13,'catorce':14,'quince':15,'dieciseis':16,'dieciséis':16,
            'diecisiete':17,'dieciocho':18,'diecinueve':19,'veinte':20,'veintiuno':21,'veintiún':21,'veintidos':22,'veintidós':22,
            'veintitres':23,'veintitrés':23,'veinticuatro':24,'veinticinco':25,'veintiseis':26,'veintiséis':26,
            'veintisiete':27,'veintiocho':28,'veintinueve':29,'treinta':30}

def word_to_day(s):
    s = s.strip().lower()
    if s == 'primero': return 1
    if s in UNIDADES: return UNIDADES[s]
    if re.match(r'treinta y (uno|un)', s): return 31
    return None

def word_to_year(s):
    s = s.strip().lower()
    m = re.search(r'dos mil ([a-záéíóúñ]*)', s)
    if not m: return None
    rest = m.group(1).strip()
    return 2000 if not rest else 2000 + UNIDADES.get(rest, 0)

def parse_fecha(text):
    m = re.search(r'del ([a-záéíóúñ]+(?: y [a-záéíóúñ]+)?) de ([a-záéíóúñ]+) del?(?: año)? (dos mil [a-záéíóúñ]*)', text, re.IGNORECASE)
    if m:
        day_w, month_w, year_w = m.groups()
        day = word_to_day(day_w); month = MESES.get(month_w.lower()); year = word_to_year(year_w)
        if day and month and year: return f"{day:02d}/{month:02d}/{year}"
    m1b = re.search(r'del ([a-záéíóúñ]+(?: y [a-záéíóúñ]+)?) de ([a-záéíóúñ]+) del?\s*(\d{4})\b', text, re.IGNORECASE)
    if m1b:
        day_w, month_w, year_s = m1b.groups()
        day = word_to_day(day_w); month = MESES.get(month_w.lower())
        if day and month:
            return f"{day:02d}/{month:02d}/{year_s}"
    m2 = re.search(r'se se[ñn]alan?\s*las?[^\n]{0,30}?del\s*(\d{1,2})[-/](\d{1,2})[-/](\d{4})', text, re.IGNORECASE)
    if m2:
        d, mo, y = m2.groups()
        return f"{int(d):02d}/{int(mo):02d}/{y}"
    return None

MES_RE = r'(enero|febrero|marzo|abril|mayo|junio|julio|agosto|setiembre|septiembre|octubre|noviembre|diciembre)'
DIA_RE = r'(\d{1,2}|primero|[a-záéíóúñ]+(?: y [a-záéíóúñ]+)?)'
ANIO_RE = r'(dos mil(?: [a-záéíóúñ]+)?|\d{4})'
FECHA_HORAS_RE = re.compile(r'horas[^.]{0,80}?\bdel?\s+' + DIA_RE + r'\s+de\s+' + MES_RE + r'\s+(?:del?\s+)?(?:año\s+)?' + ANIO_RE, re.IGNORECASE)
FECHA_NUM_RE = re.compile(r'\bdel?\s+(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b', re.IGNORECASE)

def fecha_en(seg):
    """Primera fecha de remate (precedida por 'horas ... del') dentro de un segmento, como dd/mm/aaaa."""
    if not seg:
        return None
    m = FECHA_HORAS_RE.search(seg)
    if m:
        d_w, mes_w, a_w = m.groups()
        d = int(d_w) if d_w.isdigit() else word_to_day(d_w)
        a = int(a_w) if a_w.isdigit() else word_to_year(a_w)
        mes = MESES.get(mes_w.lower())
        if d and mes and a:
            return f"{d:02d}/{mes:02d}/{a}"
    m = FECHA_NUM_RE.search(seg)
    if m:
        d, mo, y = m.groups()
        return f"{int(d):02d}/{int(mo):02d}/{y}"
    return None

SEGUNDO_RE = re.compile(r'segund[oa]\s+(?:remate|subasta|almoneda)', re.IGNORECASE)
TERCER_RE = re.compile(r'tercer[oa]?\s+(?:remate|subasta|almoneda)', re.IGNORECASE)
FIN_RE = re.compile(r'\bNOTAS?\b|Publ[ií]quese|Se remata por|\bEXP\w*\s*[:.]', re.IGNORECASE)
BASE_RE = re.compile(r'base\s+(?:[a-záéíóúñ]+\s+)?de\s+', re.IGNORECASE)

def _num_es(s):
    """'38.788.454,88' -> 38788454.88 ; '20.000.000' -> 20000000.0"""
    s = s.strip()
    if re.fullmatch(r'\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?', s) or re.fullmatch(r'\d+(?:,\d{1,2})?', s):
        return float(s.replace('.', '').replace(',', '.'))
    if re.fullmatch(r'\d{1,3}(?:,\d{3})*\.\d{2}', s):
        return float(s.replace(',', ''))
    return None

def monto_en(seg):
    """Monto de la base dentro de un segmento: (moneda, valor) o (None, None)."""
    m = BASE_RE.search(seg or '')
    if not m:
        return None, None
    rest = seg[m.end():m.end()+400]
    raw = re.split(r'\(|\bse se[ñn]al|\ba las\b|\bpara\b|;', rest, maxsplit=1, flags=re.IGNORECASE)[0].strip(' ,.')
    moneda, val = parse_amount_numeric(raw)
    if val is None:
        # número escrito (ej. ¢15.750.000,00 o (38.788.454,88)); nunca un porcentaje como "(25%"
        mp = re.search(r'([¢₡$]?)\s*(\d[\d\.,]*\d)(?!\s*%)(?![\d\.,])', rest)
        if mp:
            val = _num_es(mp.group(2))
            moneda = moneda or ({'¢': '₡'}.get(mp.group(1), mp.group(1)) or None)
    return moneda, val

def remates_posteriores(text, start):
    """Devuelve (seg1_fin, monto2, fecha2, monto3, fecha3) a partir de la posición start."""
    sub = text[start:]
    m2 = SEGUNDO_RE.search(sub)
    m3 = TERCER_RE.search(sub, m2.end() if m2 else 0)
    fin1 = m2.start() if m2 else (m3.start() if m3 else len(sub))
    res = {'fin1': start + fin1, 'monto2': None, 'fecha2': None, 'monto3': None, 'fecha3': None}
    if m2:
        end2 = m3.start() if m3 else (lambda f: m2.end() + f.start() if f else m2.end() + 500)(FIN_RE.search(sub, m2.end()))
        seg2 = sub[m2.start():end2]
        res['monto2'] = monto_en(seg2)[1]
        res['fecha2'] = fecha_en(seg2)
    if m3:
        f = FIN_RE.search(sub, m3.end())
        seg3 = sub[m3.start():(f.start() if f else m3.end() + 500)]
        res['monto3'] = monto_en(seg3)[1]
        res['fecha3'] = fecha_en(seg3)
    return res

def extract_expediente(text):
    m = re.search(r'EXP\w*\s*[:.]{0,2}\s*(?:N[uú°]?\.?\s*)?([\d]{2}-\s?[\d]{6}\s?-?[\d]{4}-[A-Za-z]{2})\s*,?\s*JUZGADO', text, re.IGNORECASE)
    if m:
        return re.sub(r'\s+', '', m.group(1))
    m = re.search(r'EXP\w*\s*[:.]{0,2}\s*(?:N[uú°]?\.?\s*)?([\d]{2}-\s?[\d]{6}\s?-?[\d]{4}-[A-Za-z]{2})', text, re.IGNORECASE)
    if m:
        return re.sub(r'\s+', '', m.group(1))
    m = re.search(r'expediente\s*n[uú]mero\s*([\d]{2}-\s?[\d]{6}\s?-?[\d]{4}-[A-Za-z]{2})', text, re.IGNORECASE)
    if m:
        return re.sub(r'\s+', '', m.group(1))
    m = re.search(r'EXP\w*\s*[:.]{0,2}\s*(?:N[uú°]?\.?\s*)?([\d\-A-Za-z]+)', text, re.IGNORECASE)
    return m.group(1).rstrip('.') if m else ''

def extract_juzgado(text):
    m_hs = re.search(r'\b(?:Juez|Jueza)\s+del\s+(Juzgado[^;,.]+?)\s*[;,.]?\s*HACE SABER', text, re.IGNORECASE)
    if m_hs:
        return m_hs.group(1).strip()
    m_hs2 = re.match(r'\s*(?:EL|LA)?\s*(Juzgado[^,]+?)\s*,\s*hace saber', text, re.IGNORECASE)
    if m_hs2:
        return m_hs2.group(1).strip()
    m_before = re.search(r'((?:JUZGADO|TRIBUNAL)[^.]{0,100}?)\s*\.\s*EXP\w*\s*[:.]', text, re.IGNORECASE)
    if m_before:
        return m_before.group(1).strip()
    m_exp = re.search(r'EXP\w*\s*[:.]{0,2}\s*(?:N[uú°]?\.?\s*)?[\d]{2}-\s?[\d]{6}\s?-?[\d]{4}-[A-Za-z]{2}\s*,?\s*(?=JUZGADO)', text, re.IGNORECASE)
    if not m_exp:
        m_exp = re.search(r'EXP\w*\s*[:.]{0,2}\s*(?:N[uú°]?\.?\s*)?[\d]{2}-\s?[\d]{6}\s?-?[\d]{4}-[A-Za-z]{2}', text, re.IGNORECASE)
    if not m_exp:
        m_exp = re.search(r'EXP\w*\s*[:.]{0,2}\s*(?:N[uú°]?\.?\s*)?[\d\-A-Za-z]+', text, re.IGNORECASE)
    if not m_exp:
        return ''
    seg = text[m_exp.end():m_exp.end()+300].lstrip('. ')
    juzgado = seg.split('.')[0].strip(' .')
    cutoff_m = re.search(r'\bpara tal efecto\b|\bse se[ñn]alan?\b|\bhora y fecha de emisi[oó]n\b|\d{1,2}\s+de\s+[a-záéíóúñ]+\s+del?\s+año\s+\d{4}', juzgado, re.IGNORECASE)
    if cutoff_m:
        juzgado = juzgado[:cutoff_m.start()].strip(' .,;')
    if not juzgado or len(juzgado) > 120:
        m_date = re.search(r'\d{1,2}\s+de\s+[a-záéíóúñ]+\s+del?\s+año\s+\d{4}', seg, re.IGNORECASE)
        if m_date:
            juzgado = seg[:m_date.start()].strip(' .')
    juzgado = re.sub(r'^[,;:\s]+', '', juzgado)
    return juzgado

BENEFICIARIO_RE = re.compile(
    r'(?i:PROCESO\s+(?:[A-ZÁÉÍÓÚÑ]+\s+){1,8}?)de:?\s+(.+?)\s+(?i:contra)\s+(.+?)\s+(?i:EXP\b|expediente)')
BENEFICIARIO_RE2 = re.compile(
    r'proceso\s+.{0,60}?interpuesto\s+por\s+(.+?)\s+contra\s+(?:la\s+|el\s+)?(.+?)\s*[,.]?\s*EXP', re.IGNORECASE)

def extract_beneficiario(text):
    if re.search(r'interpuesto\s+por', text, re.IGNORECASE):
        m2 = BENEFICIARIO_RE2.search(text)
        if m2:
            return m2.group(1).strip(' .,'), m2.group(2).strip(' .,')
    m = BENEFICIARIO_RE.search(text)
    if m:
        benef = m.group(1).strip(' .,')
        demandado = m.group(2).split(',')[0].strip(' .,')
        return benef, demandado
    m2 = BENEFICIARIO_RE2.search(text)
    if m2:
        return m2.group(1).strip(' .,'), m2.group(2).strip(' .,')
    return '', ''

def base_and_grav(text, idx_start, idx_fin=None):
    """idx_fin: donde empieza el segundo remate (para no tomar su base como la del primero)."""
    before = text[:idx_start]
    base_m = re.search(r'base\s+(?:[a-záéíóúñ]+\s+)?de\s+(.*)', before, re.IGNORECASE)
    if base_m:
        base_full = base_m.group(1)
    else:
        after = text[idx_start:idx_fin]
        base_m2 = re.search(r'base\s+(?:[a-záéíóúñ]+\s+)?de\s+(.*)', after, re.IGNORECASE)
        base_full = base_m2.group(1) if base_m2 else ''
    grav_m = re.search(r'(libre de gravámenes.*|soportando.*)', base_full, re.IGNORECASE)
    if grav_m:
        base_amt = base_full[:grav_m.start()].strip(' ,;')
        grav = grav_m.group(1).strip(' ,;.')
    else:
        cut_m = re.search(r'[)\.]', base_full)
        base_amt = (base_full[:cut_m.start()] if cut_m else base_full).strip(' ,;()')
        grav = ''
    return base_amt, grav

VEH_LABELS = ['Año Fabricación','Año de Fabricación','Ano Fabricacion','Ano de Fabricacion',
              'Número de Serie','Numero de Serie','N. de Serie','# de Serie','N de Serie',
              '# de Chasis','Número Chasis','Numero Chasis','N. Motor','Número Motor','Numero Motor',
              'N Motor','Motor N','Motor Nº','Motor N°','Nº Motor','N. de Motor',
              'Marca','Maraca','Estilo y Modelo','Estilo','Categoría','Categoria','Clase','Capacidad','Color','Tracción','Carrocería','Carroceria','Combustible',
              'Cilindrada','Cilindros','Potencia','VIN','Vin','Chasis','Serie','Placas','Placa','Modelo','Uso','Año','AO']
VEH_LABELS_SORTED = sorted(set(VEH_LABELS), key=len, reverse=True)
label_pattern = re.compile(r'\b(' + '|'.join(re.escape(l) for l in VEH_LABELS_SORTED) + r')\s*:?\s*', re.IGNORECASE)

def parse_vehiculo_fields(seg):
    matches = list(label_pattern.finditer(seg))
    filtered = []
    for m in matches:
        label = m.group(1).strip().lower()
        if label in ('año', 'ao'):
            # "... de noviembre del año 2025" es una fecha, no el año del vehículo
            if re.search(r'\bdel?\s+$', seg[:m.start()], re.IGNORECASE):
                continue
            # "año dos mil..." sin dos puntos también es una fecha; "año: dos mil diez" sí es el dato
            tail = seg[m.end():m.end()+20]
            if ':' not in m.group(0) and re.match(r'\s*dos mil\b', tail, re.IGNORECASE):
                continue
        filtered.append(m)
    matches = filtered
    fields = {}
    for i, m in enumerate(matches):
        label = m.group(1).strip().lower()
        start = m.end()
        if i+1 < len(matches):
            end = matches[i+1].start()
        else:
            window = seg[start:start+120]
            stop_m = re.search(r'[.;]', window)
            end = start + (stop_m.start() if stop_m else min(len(window), 40))
        value = seg[start:end].strip(' ,.;')
        fields.setdefault(label, value)
    return fields

def normalize_placa(raw):
    if not raw:
        return raw
    raw = raw.strip(' .,;')
    raw = re.sub(r'^(n[uú]mero|n[°ºo]\.?|#)\s*', '', raw, flags=re.IGNORECASE).strip()
    raw = re.sub(r'-\s+', '-', raw)
    m = re.match(r'^([A-Za-z]{1,4})[\s\-]+([0-9]{3,7})$', raw)
    if m:
        prefix, num = m.groups()
        if prefix.upper() in ('MOT','CL','TL','TSJ','C'):
            return f"{prefix.upper()}-{num}"
    return raw

def get_veh_data(text, idx_end):
    seg = text[idx_end:idx_end+700]
    no_inscrito = bool(re.match(r'\s*(?:(?:el|del)?\s*veh[ií]culo\s*){1,2}(?:NO\s+INSCRITO|SIN\s+PLACA)', seg, re.IGNORECASE))
    fields = parse_vehiculo_fields(seg)
    placa = None
    if not no_inscrito:
        for key in ['placas','placa']:
            if key in fields:
                raw = fields[key] or ''
                m_cut = re.search(r'[,.;]', raw)
                raw_placa = (raw[:m_cut.start()] if m_cut else raw).strip()
                placa = normalize_placa(raw_placa)
                extra = raw[len(raw_placa):].strip(' ,.;')
                if extra and not fields.get('marca') and not fields.get('maraca'):
                    fields['marca'] = extra
                break
        if not placa:
            seg2 = re.sub(r'^\s*(?:(?:el|del)?\s*veh[ií]culo\s*:?\s*){1,2}', '', seg, flags=re.IGNORECASE)
            seg2 = re.sub(r'^\s*(?:n[uú]mero|n[°ºo]\.?|#)\s*', '', seg2, flags=re.IGNORECASE)
            m = re.match(r'\s*([A-Z0-9][A-Z0-9\-]{3,12})\b', seg2)
            if m:
                placa = normalize_placa(m.group(1))
            else:
                m2b = re.match(r'\s*([A-Za-z]{1,4})\s+(\d{3,7})\b', seg2)
                if m2b:
                    placa = normalize_placa(f"{m2b.group(1)}-{m2b.group(2)}")
                    rest_after = seg2[m2b.end():]
                    m_brand = re.match(r'\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ0-9]{1,20})\s+(?=Estilo|Categor)', rest_after)
                    if m_brand and not fields.get('marca') and not fields.get('maraca'):
                        fields['marca'] = m_brand.group(1)
    marca = fields.get('marca') or fields.get('maraca') or ''
    estilo = fields.get('estilo') or fields.get('estilo y modelo') or ''
    estilo = re.split(r',?\s*CATEGOR[IÍ]A', estilo, flags=re.IGNORECASE)[0].strip()
    marca = re.split(r',?\s*CATEGOR[IÍ]A', marca, flags=re.IGNORECASE)[0].strip()
    anio = fields.get('año fabricación') or fields.get('año de fabricación') or fields.get('ano fabricacion') or fields.get('ano de fabricacion') or fields.get('año') or fields.get('ao') or ''
    if anio and not re.search(r'\d', anio):
        anio_num = words_to_int(anio)          # "dos mil diez" -> 2010
        anio = str(anio_num) if anio_num else anio
    anio = re.sub(r'\D','', anio)[:4]
    placa = (placa or '').strip(' .#:')
    if not no_inscrito and (not placa or placa == '#'):
        m2 = re.search(r'PLACA[S]?\s*#?\s*:?\s*([A-Z0-9][A-Z0-9\-]{2,12})', seg, re.IGNORECASE)
        if m2:
            placa = normalize_placa(m2.group(1))
    return placa, marca.strip(' .'), estilo.strip(' .'), anio

PROVINCIAS = ['SAN JOSÉ','SAN JOSE','ALAJUELA','CARTAGO','HEREDIA','GUANACASTE','PUNTARENAS','LIMÓN','LIMON']

def find_provincia(seg_after):
    for prov in sorted(PROVINCIAS, key=len, reverse=True):
        m = re.search(r'\b' + prov.replace('É','[EÉ]').replace('Ó','[OÓ]') + r'\b', seg_after, re.IGNORECASE)
        if m:
            name = m.group(0).upper()
            if name in ('SAN JOSE',): name = 'SAN JOSÉ'
            if name in ('LIMON',): name = 'LIMÓN'
            return name, m.start(), m.end()
    return '', -1, -1

NUM_WORD_PREFIX_RE = re.compile(
    r'^(?:primero|primer|cero|uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|'
    r'dieciseis|dieciséis|diecisiete|dieciocho|diecinueve|veinte|veintiuno|veintiún|veintidos|veintidós|veintitres|veintitrés|'
    r'veinticuatro|veinticinco|veintiseis|veintiséis|veintisiete|veintiocho|veintinueve|treinta)\s*:?\s*(?:de\s+)?', re.IGNORECASE)

def strip_code_prefix(s):
    original = s.strip(' ,.')
    s2 = re.sub(r'^[0-9\-\s]+','', s)
    s2 = NUM_WORD_PREFIX_RE.sub('', s2)
    s2 = re.sub(r'\s+del?$', '', s2.strip(), flags=re.IGNORECASE)
    s2 = s2.strip(' ,.')
    return s2 if s2 else original

def parse_finca_fields(text, idx_end):
    seg = text[idx_end:idx_end+1600]
    out = {'matricula':'', 'derechos':'', 'medida':'', 'distrito':'', 'canton':'', 'provincia':'', 'plano':''}
    m = re.search(r'matr[ií]cula\s*(?:de folio real\s*)?(?:n[uú]mero\s*)?([^,]+?),\s*derecho[s]?\s*(.+?),?\s*la cual es', seg, re.IGNORECASE)
    if m:
        out['matricula'] = m.group(1).strip()
        out['derechos'] = m.group(2).strip(' ,')
    else:
        m1b = re.search(r'matr[ií]cula\s*(?:de folio real\s*)?(?:n[uú]mero\s*)?([^,]+?),\s*derecho[s]?\s*([^,\.]+)', seg, re.IGNORECASE)
        if m1b:
            out['matricula'] = m1b.group(1).strip()
            out['derechos'] = m1b.group(2).strip()
        else:
            m1c = re.search(r'matr[ií]cula\s*(?:de folio real\s*)?(?:n[uú]mero\s*)?([0-9A-Za-z\-]+)', seg, re.IGNORECASE)
            if m1c:
                out['matricula'] = m1c.group(1).strip()
            else:
                m1d = re.search(r'partido de\s*[A-ZÁÉÍÓÚÑ]+-([0-9A-Za-z\-]+),\s*la cual es', seg, re.IGNORECASE)
                if m1d:
                    out['matricula'] = m1d.group(1).strip()
                else:
                    # el valor debe tener algún dígito: "la finca: FINCA: 123456" no es la matrícula "FINCA"
                    m1e = re.search(r'\bFINCA:\s*(?=[0-9A-Za-z\-]*\d)([0-9A-Za-z\-]+)', seg, re.IGNORECASE)
                    if m1e:
                        out['matricula'] = m1e.group(1).strip()
                        m1e_der = re.search(r'\bDERECHO:\s*([0-9A-Za-z\-]+)', seg, re.IGNORECASE)
                        if m1e_der:
                            out['derechos'] = m1e_der.group(1).strip()
                    else:
                        m1f = re.search(r'\bfinca\s+([0-9A-Za-z\-]+)\s+derecho\s+([0-9A-Za-z\-]+)', seg, re.IGNORECASE)
                        if m1f:
                            out['matricula'] = m1f.group(1).strip()
                            out['derechos'] = m1f.group(2).strip()
                        else:
                            m1g = re.search(r'partido de\s*[A-ZÁÉÍÓÚÑ]+(?:\s+[A-ZÁÉÍÓÚÑ]+)*\s+n[uú]mero\s+([0-9A-Za-z\-]+)\s+DERECHO:?\s*([0-9A-Za-z\-]+)', seg, re.IGNORECASE)
                            if m1g:
                                out['matricula'] = m1g.group(1).strip()
                                out['derechos'] = m1g.group(2).strip()
                            else:
                                m1h = re.search(r'n[uú]mero de finca\s+([0-9A-Za-z\-]+)', seg, re.IGNORECASE)
                                if m1h:
                                    out['matricula'] = m1h.group(1).strip()
    # un punto seguido de dígito es separador de miles/decimales ("MIDE: 1.500 METROS"), no fin de frase
    m2 = re.search(r'MIDE[:.]?\s*(.*?)(?=PLANO:|\.(?!\d)|$)', seg, re.IGNORECASE)
    if m2:
        out['medida'] = m2.group(1).strip(' .')
    else:
        m2b = re.search(r'(?:con\s+un[a]?\s+)?[aá]rea\s+de\s+(.*?)(?=,\s*ubicad|\.(?!\d)|$)', seg, re.IGNORECASE)
        if m2b:
            out['medida'] = m2b.group(1).strip(' .')
    m_dist = re.search(r'DISTRITO\s*', seg, re.IGNORECASE)
    m_cant = re.search(r'CANT?[OÓ]N\s*', seg, re.IGNORECASE)
    m_prov = re.search(r'(?:DE\s+LA\s+PROVINCIA\s+(?:DE\s+)?|provincia\s+(?:de\s+)?)', seg, re.IGNORECASE)
    if m_dist and m_cant and m_cant.start() < m_dist.end():
        first_kw, second_kw = m_cant, m_dist
        canton_raw = seg[first_kw.end():second_kw.start()].strip(' ,.')
        canton_raw = re.sub(r'^en el\s+', '', canton_raw, flags=re.IGNORECASE).strip(' ,.')
        canton_raw = re.sub(r',?\s*en el\s*$', '', canton_raw, flags=re.IGNORECASE).strip(' ,.')
        out['canton'] = strip_code_prefix(canton_raw)
        dist_end = m_prov.start() if (m_prov and m_prov.start() > second_kw.end()) else second_kw.end()+40
        distrito_raw = seg[second_kw.end():dist_end].strip(' ,.')
        out['distrito'] = strip_code_prefix(distrito_raw)
    else:
        if m_dist and m_cant and m_cant.start() > m_dist.end():
            distrito_raw = seg[m_dist.end():m_cant.start()].strip(' ,.')
            out['distrito'] = strip_code_prefix(distrito_raw)
        if m_cant:
            m_stop = re.search(r'naturaleza:', seg[m_cant.end():], re.IGNORECASE)
            if m_prov and m_prov.start() > m_cant.end():
                cant_end = m_prov.start()
            elif m_stop:
                cant_end = m_cant.end() + m_stop.start()
            else:
                cant_end = m_cant.end()+40
            canton_raw = seg[m_cant.end():cant_end].strip(' ,.')
            canton_raw = re.sub(r'CANT?[OÓ]N', '', canton_raw, flags=re.IGNORECASE)
            canton_raw = canton_raw.strip(' ,.')
            canton_raw = strip_code_prefix(canton_raw)
            out['canton'] = canton_raw
    if m_prov:
        window = seg[m_prov.end():m_prov.end()+60]
        prov, _, _ = find_provincia(window)
        out['provincia'] = prov
    if not out['provincia']:
        prov, _, _ = find_provincia(seg[:400])
        out['provincia'] = prov
    m4 = re.search(r'PLAN[OP]\w*\s*(?:catastrado\s*)?(?:n[uú]mero\s*)?:?\s*([A-Za-z]{0,3}-?\d[\dA-Za-z\-]*)', seg, re.IGNORECASE)
    if m4: out['plano'] = m4.group(1).strip()
    desc = ''
    md = re.search(r'la cual es\s*(.+?)(?:\s*[Ss]ituada en|\s*SITUADA EN|\s*MIDE:)', seg, re.IGNORECASE)
    if md:
        desc = md.group(1).strip(' .-')
    else:
        # "NATURALEZA:" es la etiqueta explícita; se prueba antes que "derecho ..., <descripción>"
        # (esa variante cruza "SITUADA EN" y devolvía la ubicación como descripción)
        md3 = re.search(r'NATURALEZA:\s*(.+?)(?:\s*SITUADA EN|\s*MIDE:)', seg, re.IGNORECASE)
        if md3:
            desc = md3.group(1).strip(' .-')
        else:
            md2 = re.search(r'derecho[s]?\s*[^\.,]+,\s*(?:la cual es\s*)?(.+?)(?:\s*[Ss]ituada en|\s*SITUADA EN|\s*MIDE:)', seg, re.IGNORECASE)
            if md2:
                desc = md2.group(1).strip(' .-')
    out['descripcion'] = limpiar_descripcion(desc.strip(' .-'))
    return out

# "FINCA SE ENCUENTRA EN ZONA CATASTRADA" es una nota catastral, no parte de la descripción
# (a veces llega como "INCA SE ENCUENTRA...", sin la F)
ZONA_CATASTRADA_RE = re.compile(
    r'[,.;]?\s*\bF?INCA\s+SE\s+ENCUENTRA\s+EN\s+ZONA\s+(?:NO\s+)?CATASTRADA\b[,.;]?', re.IGNORECASE)

# "la cual es terreno TERRENO PARA CONSTRUIR": el "terreno" del edicto más el del registro
TERRENO_REPETIDO_RE = re.compile(r'^terreno\s*(?=terreno\b)', re.IGNORECASE)

# "... matrícula ..., soportando ... Dicha finca se describe así: Terreno para construir": lo anterior no es descripción
SE_DESCRIBE_RE = re.compile(r'\b(?:dicha\s+finca\s+|que\s+)?se\s+describe\s+as[ií]\s*:?\s*', re.IGNORECASE)
# "la cual es la cual es terreno ...": el edicto repite la frase
LA_CUAL_ES_RE = re.compile(r'^la\s+cual\s+es\s+', re.IGNORECASE)

def limpiar_descripcion(desc):
    """Etiqueta NATURALEZA, frase "Dicha finca se describe así:", nota de zona catastrada, puntuación sobrante
    en los extremos ("TERRENO CON 1 CASA," -> "TERRENO CON 1 CASA"), "la cual es" inicial y "terreno TERRENO"."""
    desc = limpiar_naturaleza(desc)
    m = SE_DESCRIBE_RE.search(desc)
    if m:
        desc = desc[m.end():]
    desc = ZONA_CATASTRADA_RE.sub('', desc).strip(' .,;:-')
    desc = LA_CUAL_ES_RE.sub('', desc)
    return TERRENO_REPETIDO_RE.sub('', desc)

NATURALEZA_ETIQUETA_RE = re.compile(r'\bnaturaleza\s*:\s*', re.IGNORECASE)
NATURALEZA_INICIO_RE = re.compile(r'^(?:(?:la cual es|terreno|de)\s+)*naturaleza\b\s*', re.IGNORECASE)

def limpiar_naturaleza(desc):
    """Quita la etiqueta NATURALEZA de la descripción:
    'terreno NATURALEZA: SOLAR CON UNA CASA' -> 'SOLAR CON UNA CASA';
    'de naturaleza terreno con una casa' -> 'terreno con una casa';
    '... FOLIO REAL ... DERECHO CERO. NATURALEZA: LOTE 5' -> 'LOTE 5' (lo que va antes de la etiqueta no es descripción)."""
    m = NATURALEZA_ETIQUETA_RE.search(desc)
    if m:
        return desc[m.end():].strip(' .-')
    return NATURALEZA_INICIO_RE.sub('', desc).strip(' .-')

def get_blocks(path):
    with open(path, encoding='utf-8') as f:
        content = f.read()
    lines = content.split('\n')
    blocks = []
    for i, line in enumerate(lines):
        if 'sáquese a remate' in line or 'sáquese en remate' in line:
            ref = None
            for j in range(i+1, min(i+10, len(lines))):
                m = re.search(r'Referencia N.?:\s*([0-9]+)', lines[j])
                if m: ref = m.group(1); break
            blocks.append((ref, line.strip()))
    return blocks

DERECHO_WORD_RE = re.compile(
    r'\b(cero|uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|'
    r'dieciseis|dieciséis|diecisiete|dieciocho|diecinueve|veinte|treinta)\b', re.IGNORECASE)

def format_derechos(raw):
    if not raw:
        return raw
    s = raw.strip()
    if re.search(r'\d', s) and not DERECHO_WORD_RE.search(s):
        return s
    parts = re.split(r',|\by\b', s, flags=re.IGNORECASE)
    codes = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        val = words_to_int(p)
        if val is not None:
            codes.append(f"{val:03d}")
        else:
            codes.append(p)
    return ', '.join(codes) if codes else s

def clean_derechos_display(s):
    if not s:
        return s
    s = s.strip().lstrip('-').strip()
    s = re.sub(r'\s+y\s+', ', ', s, flags=re.IGNORECASE)
    return s

def dedupe(items, key_fields):
    seen = set()
    out = []
    for it in items:
        key = tuple(it.get(f, '') for f in key_fields)
        if key in seen:
            continue
        seen.add(key)
        out.append(it)
    return out

def process_file(path):
    blocks = get_blocks(path)
    out_list = []
    for ref, text in blocks:
        idx_m = re.search(r's[aá]quese (?:a|en) remate', text)
        after = text[idx_m.end():idx_m.end()+150]
        tipo = 'otro'
        if 'finca' in after.lower(): tipo = 'finca'
        elif 'veh' in after.lower(): tipo = 'vehiculo'
        post = remates_posteriores(text, idx_m.start())
        base_amt_raw, grav = base_and_grav(text, idx_m.start(), post['fin1'])
        base_fmt = normalize_amount_symbol(parse_amount(base_amt_raw))
        moneda, monto_num = parse_amount_numeric(base_amt_raw)
        expediente = extract_expediente(text)
        juzgado = extract_juzgado(text)
        beneficiario, demandado = extract_beneficiario(text)
        seg1 = text[idx_m.start():post['fin1']]
        fecha = parse_fecha(seg1) or fecha_en(seg1) or fecha_en(text[:idx_m.start()]) or parse_fecha(text[idx_m.start():])
        grav_clean = grav[0].upper() + grav[1:] if grav else grav
        item = {'ref': ref, 'tipo': tipo, 'base_raw': base_amt_raw, 'base': base_fmt,
                 'moneda': moneda, 'monto_num': monto_num,
                 'grav': grav_clean, 'expediente': expediente, 'juzgado': juzgado,
                 'beneficiario': beneficiario, 'demandado': demandado, 'fecha': fecha,
                 'monto2': post['monto2'], 'fecha2': post['fecha2'],
                 'monto3': post['monto3'], 'fecha3': post['fecha3']}
        if tipo == 'finca':
            f = parse_finca_fields(text, idx_m.end())
            f['matricula_num'] = parse_matricula_num(f['matricula'])
            f['derechos_fmt'] = clean_derechos_display(format_derechos(f['derechos']))
            f['medida_fmt'] = parse_area(f['medida'])
            f['medida_num'] = parse_area_numeric(f['medida'])
            item.update(f)
        elif tipo == 'vehiculo':
            placa, marca, estilo, anio = get_veh_data(text, idx_m.end())
            item.update({'placa':placa,'marca':marca,'estilo':estilo,'anio':anio})
        out_list.append(item)

    fincas = [i for i in out_list if i['tipo']=='finca']
    vehs = [i for i in out_list if i['tipo']=='vehiculo']
    fincas = dedupe(fincas, ['base_raw','matricula','medida','descripcion'])
    vehs = dedupe(vehs, ['base_raw','placa','marca','estilo','anio'])
    return fincas, vehs
