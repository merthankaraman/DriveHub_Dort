import sys
import struct
from pathlib import Path

def extract_ac_data(path: Path):
    """
    AC/EVO dosyalarini her bayt ofsetinde tarayarak float degerlerini bulur.
    Ozellikle 0x0D ve 0x55 baytlarindan sonra gelen float'lara oncelik verir.
    """
    try:
        data = path.read_bytes()
    except Exception as e:
        print(f"Hata: {e}")
        return

    n = len(data)
    print(f"\n=== ANALIZ: {path.name} ({n} bytes) ===")
    
    # 1. Tum olasi floatlari her bayt ofsetinde bul
    found_floats = []
    for i in range(n - 4):
        # Little-endian float oku
        val = struct.unpack('<f', data[i:i+4])[0]
        
        # Filtre: Anlamli RPM veya Gain araliginda mi?
        # (Game engine verilerinde genellikle -10 ile 25000 arasindaki sayilarla ilgileniyoruz)
        if -10.0 <= val <= 25000.0:
            # Tag kontrolu (Kunos dosyalarinda float oncesi 0x0D yaygindir)
            has_tag = (i > 0 and data[i-1] == 0x0D)
            found_floats.append({'offset': i, 'val': val, 'tagged': has_tag})

    # 2. Sonuclari anlamli ciftler (X, Y) olarak grupla veya listele
    print(f"{'Ofset':<10} | {'Deger':<12} | {'Not'}")
    print("-" * 35)
    
    last_val = None
    last_offset = -100
    
    for f in found_floats:
        # Sadece tam 0.0 olmayan veya tagged olanlari basalim (gurultuyu azaltmak icin)
        if f['val'] == 0.0 and not f['tagged']:
            continue
            
        note = "[TAGGED]" if f['tagged'] else ""
        
        # Eger bir onceki float ile arasinda 4-12 bayt varsa muhtemelen bir (X, Y) ciftidir
        if 4 <= (f['offset'] - last_offset) <= 12:
            print(f"0x{f['offset']:04X}     | {f['val']:<12.4f} | --> Cift adayi (Pre: {last_val:.2f})")
        else:
            # Sadece yuksek RPM'leri veya tagged olanlari goster
            if f['val'] > 500 or f['tagged'] or f['val'] < -0.1:
                print(f"0x{f['offset']:04X}     | {f['val']:<12.4f} | {note}")
        
        last_val = f['val']
        last_offset = f['offset']

def main():
    if len(sys.argv) < 2:
        print("Kullanim: python script.py <dosya_yolu>")
        return
    
    target = Path(sys.argv[1])
    if target.is_dir():
        for f in target.glob("*.curve"): extract_ac_data(f)
        for f in target.glob("*.carengine"): extract_ac_data(f)
    else:
        extract_ac_data(target)

if __name__ == "__main__":
    main()