#!/usr/bin/env python3
"""Uso: python procesar_boletin.py boletin.txt [salida.xlsx]
El Excel se guarda en la carpeta rem/Excel/ (salvo que salida.xlsx incluya otra carpeta).
Lee un Boletín Judicial (.txt) y genera un Excel con remates de terrenos y vehículos."""
import sys, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import openpyxl
from openpyxl.styles import Font, Alignment, PatternFill
from openpyxl.utils import get_column_letter
from parse_full3 import process_file

# Nombres de las pestañas del Excel (los mismos que usa la app en Google Sheets)
HOJA_TERRENOS = "Terrenos"
HOJA_VEHICULOS = "Vehiculos"

TERRENOS_HEADERS = ["Número de boletín", "Referencia N°", "Moneda", "Monto primer remate", "Fecha primer remate",
                    "Medida de la finca", "Distrito", "Cantón", "Provincia", "Descripción del terreno",
                    "Gravámenes y anotaciones",
                    "Monto segundo remate", "Fecha segundo remate", "Monto tercer remate", "Fecha tercer remate",
                    "Número de matrícula", "Número de plano", "Expediente", "Juzgado", "Beneficiario",
                    "Afectado", "Estado"]
TERRENOS_WIDTHS = [12, 16, 9, 16, 14, 12, 16, 16, 14, 38, 42, 16, 14, 16, 14, 26, 16, 20, 42, 34, 34, 14]

VEH_HEADERS = ["Número de boletín", "Referencia N°", "Placa", "Moneda", "Monto primer remate", "Fecha primer remate",
               "Marca", "Estilo", "Año de fabricación", "Gravámenes y anotaciones",
               "Monto segundo remate", "Fecha segundo remate", "Monto tercer remate", "Fecha tercer remate",
               "Expediente", "Juzgado", "Beneficiario", "Afectado", "Estado"]
VEH_WIDTHS = [12, 16, 14, 9, 16, 14, 22, 24, 12, 42, 16, 14, 16, 14, 20, 42, 34, 34, 14]

# Columnas numéricas (se les aplica formato #,##0.00)
NUM_COLS = {"Monto primer remate", "Medida de la finca", "Monto segundo remate", "Monto tercer remate"}
NUM_FORMAT = '[$-409]#,##0.00'
NI = 'No indicado'

def _v(x):
    return x if x not in (None, '') else NI

def fila_terreno(it, boletin_num):
    matricula = it['matricula_num'] or NI
    if it.get('derechos_fmt'):
        matricula = f"{matricula} (der. {it['derechos_fmt']})"
    return [boletin_num, it['ref'], _v(it.get('moneda')), _v(it.get('monto_num')), _v(it['fecha']),
            _v(it.get('medida_num')), _v(it['distrito']), _v(it['canton']), _v(it['provincia']),
            _v(it['descripcion']), _v(it['grav']),
            _v(it.get('monto2')), _v(it.get('fecha2')), _v(it.get('monto3')), _v(it.get('fecha3')),
            matricula, _v(it['plano']), _v(it['expediente']), _v(it.get('juzgado')), _v(it.get('beneficiario')),
            _v(it.get('demandado')), None]   # Afectado = la persona contra quien es el proceso; Estado vacío

def fila_vehiculo(it, boletin_num):
    return [boletin_num, it['ref'], _v(it['placa']), _v(it.get('moneda')), _v(it.get('monto_num')), _v(it['fecha']),
            _v(it['marca']), _v(it['estilo']), _v(it['anio']), _v(it['grav']),
            _v(it.get('monto2')), _v(it.get('fecha2')), _v(it.get('monto3')), _v(it.get('fecha3')),
            _v(it['expediente']), _v(it.get('juzgado')), _v(it.get('beneficiario')),
            _v(it.get('demandado')), None]   # Afectado; Estado vacío

def build_workbook(data, boletin_num, out_path):
    wb = openpyxl.Workbook()
    ws1 = wb.active
    ws1.title = HOJA_TERRENOS
    ws2 = wb.create_sheet(HOJA_VEHICULOS)
    hojas = [(ws1, TERRENOS_HEADERS, TERRENOS_WIDTHS, [fila_terreno(it, boletin_num) for it in data['fincas']]),
             (ws2, VEH_HEADERS, VEH_WIDTHS, [fila_vehiculo(it, boletin_num) for it in data['vehiculos']])]

    header_fill = PatternFill(start_color="1F4E78", end_color="1F4E78", fill_type="solid")
    header_font = Font(name="Arial", bold=True, color="FFFFFF", size=11)
    body_font = Font(name="Arial", size=10)

    for ws, headers, widths, filas in hojas:
        ws.append(headers)
        num_idx = [i for i, h in enumerate(headers, start=1) if h in NUM_COLS]
        for fila in filas:
            ws.append(fila)
            r = ws.max_row
            for c in num_idx:
                if isinstance(ws.cell(row=r, column=c).value, (int, float)):
                    ws.cell(row=r, column=c).number_format = NUM_FORMAT
        for cell in ws[1]:
            cell.fill = header_fill
            cell.font = header_font
            cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        ws.freeze_panes = "A2"
        ws.row_dimensions[1].height = 30
        for row in ws.iter_rows(min_row=2):
            for cell in row:
                cell.font = body_font
                cell.alignment = Alignment(vertical="center", wrap_text=True)
        for idx, w in enumerate(widths, start=1):
            ws.column_dimensions[get_column_letter(idx)].width = w

    wb.save(out_path)
    return out_path


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    path = sys.argv[1]
    with open(path, encoding='utf-8') as f:
        head_lines = [f.readline() for _ in range(3)]
    num = os.path.splitext(os.path.basename(path))[0]
    for line in head_lines:
        m = re.search(r'n[uú]mero\s*(\d+)', line, re.IGNORECASE)
        if m: num = m.group(1); break
    out = sys.argv[2] if len(sys.argv) > 2 else f'Remates_Boletin_{num}.xlsx'
    # Si la salida no indica carpeta, se guarda en rem/Excel/
    if not os.path.dirname(out):
        excel_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'Excel')  # rem/Excel
        os.makedirs(excel_dir, exist_ok=True)
        out = os.path.join(excel_dir, out)
    fincas, vehs = process_file(path)
    build_workbook({'fincas': fincas, 'vehiculos': vehs}, num, out)
    print(f'Guardado {out}: {len(fincas)} terrenos, {len(vehs)} vehículos')

if __name__ == '__main__':
    main()
