Taruh di sini paket patch winebus/setupapi/hidclass hasil build repo winebus-test, satu file per versi mayor Proton:

  proton-9.tar.zst    -> Proton 9.x  arm64ec (default /opt/proton-9.0-arm64ec)
  proton-10.tar.zst   -> Proton 10.x arm64ec (10.0-4, 10.0-5, ...), contents Proton
  proton-11.tar.zst   -> Proton 11.x arm64ec (11.0-1, 11.0-2, ...), contents Proton

Nama file hanya memakai angka mayor, jadi satu paket berlaku untuk semua rilis minor (10.0-4 dan 10.0-5 memakai proton-10.tar.zst).
Versi mayor baru cukup ditambah filenya (proton-12.tar.zst), tanpa mengubah kode.

Tata letak di dalam tar (otomatis dikenali, urutan prioritas):
  1. lib/wine/<arch>-unix|windows/...   -> digabung ke akar instalasi Proton
  2. wine/<arch>-unix|windows/...       -> digabung ke <akar>/lib/wine
  3. <arch>-unix|windows/...            -> digabung ke <akar>/lib/wine
  4. file datar (winebus.so, setupapi.dll, ...) -> menimpa file bernama sama yang sudah ada di lib/wine
Isi tar yang sudah ada di tujuan akan ditimpa. Kegagalan patch tidak menggagalkan instalasi Proton.
