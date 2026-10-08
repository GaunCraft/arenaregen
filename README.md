# ArenaRegen

Paper/Purpur 1.21.x icin arena dunyasi blok yenileme plugini. Bloklar degistikten (patlama, kirma, koyma, yanma, su/lav, dusen blok) belirli sure sonra eski haline doner.

## Jar'i GitHub'da derleme
1. Bu klasoru yeni bir GitHub deposuna yukle (`main` dalina push et).
2. Depoda **Actions** sekmesine gir, **Build** islemi otomatik calisir (calismazsa "Run workflow" ile elle baslat).
3. Is bitince calismanin sayfasinda **Artifacts > ArenaRegen** altindan `ArenaRegen.jar` dosyasini indir.

Kalici indirme linki istersen tag at: `git tag v1.0 && git push origin v1.0`
Jar, deponun **Releases** sayfasina otomatik eklenir.

## Kurulum
1. `ArenaRegen.jar` dosyasini sunucunun `plugins/` klasorune at, sunucuyu baslat.
2. `plugins/ArenaRegen/config.yml` icinde `world:` satirini arena dunyanin klasor adiyla degistir.
3. `/arenaregen reload`

## Komutlar (yetki: `arenaregen.admin`)
- `/arenaregen status` bekleyen blok sayisi
- `/arenaregen now` bekleyen tum bloklari hemen geri koy
- `/arenaregen reload` config'i yenile

## Yerelde derleme
JDK 21 + Maven ile: `mvn package` -> `target/ArenaRegen.jar`
