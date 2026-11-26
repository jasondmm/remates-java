#!/usr/bin/env python3
"""Prints the Excel rows the Python scripts produce for every .txt in a folder, for BoletinParityTest.

Uso: python -X utf8 dump_rows.py <carpeta-python-scripts> <carpeta-txt>

Output (UTF-8), one line per item:
    #LAYOUT<TAB><key><TAB>value<TAB>value...   Excel layout: HOJAS, HEADERS_T, WIDTHS_T, HEADERS_V, WIDTHS_V, NUM_COLS
    #FILE<TAB><file name><TAB><boletín number>
    T<TAB>cell<TAB>cell...      one row of the terrenos sheet
    V<TAB>cell<TAB>cell...      one row of the vehiculos sheet
Each cell is typed: "N" (None), "F<repr(float)>", "I<int>", "S<text>" (text with \\ \t \n \r escaped).
repr(float) round-trips exactly, so the Java side can compare numbers bit for bit.
"""
import glob
import os
import re
import sys

sys.path.insert(0, os.path.abspath(sys.argv[1]))
import procesar_boletin as pb  # noqa: E402
from parse_full3 import process_file  # noqa: E402


def numero_boletin(path):
    """Same logic as procesar_boletin.main()."""
    with open(path, encoding='utf-8') as f:
        head_lines = [f.readline() for _ in range(pb.LINEAS_NUMERO)]
    return pb.numero_boletin(head_lines) or os.path.splitext(os.path.basename(path))[0]


def nombres_hojas():
    """Sheet names as procesar_boletin.py really writes them (an empty workbook built and read back)."""
    import tempfile
    import openpyxl
    with tempfile.TemporaryDirectory() as d:
        ruta = os.path.join(d, 'hojas.xlsx')
        pb.build_workbook({'fincas': [], 'vehiculos': []}, '0', ruta)
        return openpyxl.load_workbook(ruta).sheetnames


def cell(x):
    if x is None:
        return 'N'
    if isinstance(x, bool):
        return 'S' + str(x)
    if isinstance(x, float):
        return 'F' + repr(x)
    if isinstance(x, int):
        return 'I' + str(x)
    s = str(x).replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n').replace('\r', '\\r')
    return 'S' + s


out = sys.stdout
# Excel layout (column names, order, widths, numeric columns), compared with ColumnasRemate
out.write('#LAYOUT\tHOJAS\t' + '\t'.join(nombres_hojas()) + '\n')
out.write('#LAYOUT\tHEADERS_T\t' + '\t'.join(pb.TERRENOS_HEADERS) + '\n')
out.write('#LAYOUT\tWIDTHS_T\t' + '\t'.join(str(w) for w in pb.TERRENOS_WIDTHS) + '\n')
out.write('#LAYOUT\tHEADERS_V\t' + '\t'.join(pb.VEH_HEADERS) + '\n')
out.write('#LAYOUT\tWIDTHS_V\t' + '\t'.join(str(w) for w in pb.VEH_WIDTHS) + '\n')
out.write('#LAYOUT\tNUM_COLS\t' + '\t'.join(sorted(pb.NUM_COLS)) + '\n')
for path in sorted(glob.glob(os.path.join(sys.argv[2], '*.txt'))):
    num = numero_boletin(path)
    fincas, vehs = process_file(path)
    out.write(f"#FILE\t{os.path.basename(path)}\t{num}\n")
    for it in fincas:
        out.write('T\t' + '\t'.join(cell(c) for c in pb.fila_terreno(it, num)) + '\n')
    for it in vehs:
        out.write('V\t' + '\t'.join(cell(c) for c in pb.fila_vehiculo(it, num)) + '\n')
