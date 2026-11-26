#!/usr/bin/env python3
"""Procesa todos los .txt de boletines que estén en la carpeta Pendientes/.
Cada Excel se guarda en Excel/ y el .txt se mueve a Procesados/ cuando se procesa bien.
Uso: python procesar_pendientes.py"""
import os, sys, glob, shutil, subprocess

aqui = os.path.dirname(os.path.abspath(__file__))   # rem/Scripts
base = os.path.dirname(aqui)                         # rem
procesados = os.path.join(base, 'Procesados')
os.makedirs(procesados, exist_ok=True)
script = os.path.join(aqui, 'procesar_boletin.py')

pendientes = os.path.join(base, 'Pendientes')
os.makedirs(pendientes, exist_ok=True)
txts = sorted(glob.glob(os.path.join(pendientes, '*.txt')))
if not txts:
    print('No hay archivos .txt pendientes.')
for txt in txts:
    r = subprocess.run([sys.executable, script, txt], capture_output=True, text=True)
    if r.returncode == 0:
        print(r.stdout.strip())
        destino = os.path.join(procesados, os.path.basename(txt))
        if os.path.exists(destino):
            raiz, ext = os.path.splitext(destino); i = 2
            while os.path.exists(f'{raiz}_{i}{ext}'): i += 1
            destino = f'{raiz}_{i}{ext}'
        shutil.move(txt, destino)
    else:
        print(f'ERROR en {os.path.basename(txt)} (queda en la carpeta):\n{r.stderr.strip()}')
