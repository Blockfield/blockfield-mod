# WIRE-1b measurements

Harness source `32770107862e8adf3687b2969c72b5a632994a45`, JDK 21.0.12+8-LTS.

Interning table: 8706 generation-scoped refs ({'ClipRef': 765, 'ConfigRef': 1, 'DefinitionRef': 7159, 'FirstPersonRef': 16, 'MetadataRef': 765}), 504948 canonical bytes, sha256 `697eb93d8777cac2…`, handle ≤ 3 B.

## Steady per-observer S2C (KB/s, decimal; includes probe and capsules where noted)

| Cohort | Obs | Raw canonical | Raw interned | Whole zlib canonical | Whole zlib interned | RakNet stream RAW canonical | RakNet stream RAW interned | RakNet stream ZLIB interned | TCP per-packet RAW canonical | TCP RAW interned | TCP ZLIB interned | Capsules/tick |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10-rifle-idle | 0 | 4509.6 | 1376.2 | 157.9 | 82.6 | 120.7 | 58.0 | 64.0 | 618.8 | 157.6 | 78.3 | 0.00 |
| 10-rifle-combat | 0 | 5064.8 | 1509.7 | 234.4 | 138.2 | 209.9 | 114.2 | 106.4 | 826.4 | 216.2 | 134.0 | 0.90 |
| 20-rifle-idle | 0 | 9519.3 | 2904.5 | 268.3 | 144.6 | 252.8 | 126.0 | 96.9 | 1281.0 | 317.9 | 122.2 | 0.00 |
| 20-rifle-combat | 0 | 10543.8 | 3105.6 | 379.4 | 233.9 | 378.6 | 215.7 | 176.5 | 1676.4 | 417.4 | 208.1 | 0.95 |
| 30-rifle-idle | 0 | 14529.1 | 4432.8 | 377.1 | 206.2 | 382.5 | 192.4 | 134.5 | 1962.1 | 459.7 | 162.8 | 0.00 |
| 30-rifle-combat | 0 | 15968.5 | 4690.8 | 518.9 | 324.5 | 541.5 | 311.1 | 241.1 | 2502.6 | 611.9 | 273.6 | 0.95 |
| 30-rifle-combat-small | 0 | 15968.3 | 4690.7 | 515.2 | 321.4 | 538.2 | 308.8 | 237.5 | 2485.6 | 605.1 | 270.4 | 0.95 |
| 10-mixed-idle | 0 | 3669.4 | 1232.4 | 238.1 | 107.3 | 218.0 | 80.4 | 95.8 | 531.7 | 172.1 | 105.6 | 0.00 |
| 10-mixed-idle | 1 | 4088.7 | 1303.5 | 265.4 | 108.4 | 244.6 | 80.1 | 95.8 | 582.4 | 180.7 | 106.9 | 0.00 |
| 10-mixed-combat | 0 | 4109.1 | 1332.5 | 353.7 | 163.5 | 338.7 | 137.1 | 148.9 | 693.9 | 237.1 | 163.3 | 0.97 |
| 10-mixed-combat | 1 | 4561.0 | 1410.2 | 389.4 | 167.3 | 376.5 | 138.9 | 151.1 | 758.2 | 243.6 | 167.4 | 1.05 |
| 20-mixed-idle | 0 | 8677.8 | 2759.3 | 459.9 | 197.2 | 467.4 | 181.9 | 149.4 | 1243.4 | 342.0 | 173.3 | 0.00 |
| 20-mixed-idle | 1 | 9097.1 | 2830.4 | 487.0 | 198.4 | 486.8 | 183.0 | 155.2 | 1281.9 | 350.7 | 175.1 | 0.00 |
| 20-mixed-combat | 0 | 9635.8 | 2952.2 | 664.5 | 286.9 | 674.5 | 273.0 | 249.5 | 1577.6 | 451.7 | 270.5 | 1.05 |
| 20-mixed-combat | 1 | 10081.2 | 3026.7 | 697.6 | 289.7 | 712.6 | 272.3 | 248.9 | 1640.2 | 469.9 | 271.7 | 1.05 |
| 30-mixed-idle | 0 | 13685.6 | 4285.6 | 708.4 | 289.3 | 742.8 | 280.3 | 208.0 | 1951.9 | 529.3 | 234.9 | 0.00 |
| 30-mixed-idle | 1 | 14104.9 | 4356.7 | 735.3 | 290.3 | 752.6 | 283.4 | 210.0 | 2000.2 | 536.4 | 236.7 | 0.00 |
| 30-mixed-combat | 0 | 15074.8 | 4537.3 | 1000.2 | 408.9 | 1033.4 | 400.7 | 341.8 | 2442.2 | 669.6 | 367.4 | 1.05 |
| 30-mixed-combat | 1 | 15512.6 | 4610.9 | 1029.5 | 411.6 | 1068.9 | 401.6 | 344.1 | 2502.3 | 684.0 | 369.1 | 1.05 |
| 30-diverse-idle | 0 | 13686.2 | 4285.3 | 950.7 | 340.9 | 986.0 | 333.8 | 268.8 | 1989.3 | 558.1 | 305.5 | 0.00 |
| 30-diverse-idle | 1 | 14105.4 | 4356.4 | 980.1 | 346.0 | 1014.5 | 341.1 | 280.2 | 2033.7 | 564.7 | 309.7 | 0.00 |
| 30-diverse-combat | 0 | 15076.4 | 4537.9 | 1328.6 | 480.6 | 1367.4 | 474.2 | 428.4 | 2471.7 | 705.5 | 454.1 | 1.05 |
| 30-diverse-combat | 1 | 15514.3 | 4611.3 | 1362.1 | 487.9 | 1410.3 | 482.0 | 437.4 | 2532.9 | 722.4 | 461.8 | 1.05 |
| 30-mixed-combat-100 | 0 | 15431.9 | 4727.2 | 1131.6 | 493.6 | 1114.7 | 441.4 | 377.6 | 2551.7 | 760.5 | 454.1 | 4.92 |
| 30-mixed-combat-100 | 1 | 15884.7 | 4805.7 | 1168.7 | 496.1 | 1151.4 | 442.3 | 376.0 | 2616.8 | 774.5 | 455.7 | 5.05 |
| 30-mixed-combat-200 | 0 | 15521.5 | 4799.0 | 1182.8 | 538.9 | 1114.7 | 442.7 | 378.7 | 2609.0 | 809.6 | 503.2 | 9.75 |
| 30-mixed-combat-200 | 1 | 15980.4 | 4881.9 | 1222.5 | 543.7 | 1151.1 | 441.1 | 377.9 | 2677.4 | 825.1 | 507.0 | 10.05 |
| 30-mixed-combat-300 | 0 | 15595.1 | 4862.0 | 1232.1 | 583.4 | 1121.2 | 447.9 | 382.8 | 2661.8 | 858.5 | 550.6 | 14.57 |
| 30-mixed-combat-300 | 1 | 16057.0 | 4947.5 | 1273.8 | 591.1 | 1157.3 | 448.1 | 383.7 | 2732.4 | 877.9 | 557.8 | 15.05 |

## Bursts (bytes per transaction; max over the observers)

| Cohort | Phase | Raw canonical | Raw interned | Over 256 KiB (canonical/interned) | zlib interned | RakNet stream RAW interned | RakNet stream ZLIB interned | TCP ZLIB interned | Fragments RAW/ZLIB |
|---|---|---|---|---|---|---|---|---|---|
| 10-rifle-idle | FULL | 367280 | 139580 | 1/0 | 7805 | 8308 | 7068 | 7088 | 6/1 |
| 10-rifle-idle | TIMING_SEED | 525071 | 163721 | 1/0 | 8300 | 7322 | 7600 | 7688 | 7/1 |
| 10-rifle-idle | FULL_WARM | 577664 | 171764 | 1/0 | 8447 | — | — | 7649 | 7/1 |
| 10-rifle-idle | steady max | 583337 | 177437 | 2/0 | 10506 | 9596 | 9691 | 9781 | 8/1 |
| 10-rifle-combat | FULL | 373536 | 140446 | 1/0 | 9293 | 9778 | 8841 | 8860 | 6/1 |
| 10-rifle-combat | TIMING_SEED | 550095 | 167185 | 1/0 | 10393 | 10975 | 9926 | 11308 | 7/1 |
| 10-rifle-combat | FULL_WARM | 633968 | 179558 | 1/0 | 10763 | — | — | 9681 | 8/1 |
| 10-rifle-combat | steady max | 640271 | 185861 | 2/0 | 17218 | 17764 | 15684 | 17068 | 8/1 |
| 20-rifle-idle | FULL | 775331 | 294631 | 1/1 | 16184 | 17215 | 13436 | 13455 | 12/1 |
| 20-rifle-idle | TIMING_SEED | 1108442 | 345592 | 1/1 | 16829 | 16497 | 13706 | 13788 | 15/1 |
| 20-rifle-idle | FULL_WARM | 1219475 | 362575 | 1/1 | 17077 | — | — | 13677 | 15/1 |
| 20-rifle-idle | steady max | 1231448 | 374548 | 40/2 | 19236 | 18967 | 15678 | 15762 | 16/1 |
| 20-rifle-combat | FULL | 781587 | 295497 | 1/1 | 18921 | 19984 | 16146 | 16167 | 13/1 |
| 20-rifle-combat | TIMING_SEED | 1133466 | 349056 | 1/1 | 20338 | 21489 | 17430 | 18812 | 15/1 |
| 20-rifle-combat | FULL_WARM | 1338339 | 379029 | 1/1 | 21691 | — | — | 17516 | 16/1 |
| 20-rifle-combat | steady max | 1351642 | 392332 | 40/2 | 33408 | 34606 | 29399 | 31175 | 16/2 |
| 30-rifle-idle | FULL | 1183381 | 449681 | 1/1 | 23080 | 24698 | 17740 | 17759 | 19/1 |
| 30-rifle-idle | TIMING_SEED | 1691812 | 527462 | 1/1 | 23953 | 24239 | 18017 | 18101 | 22/1 |
| 30-rifle-idle | FULL_WARM | 1861285 | 553385 | 1/1 | 24285 | — | — | 18182 | 23/1 |
| 30-rifle-idle | steady max | 1879558 | 571658 | 40/2 | 27448 | 27762 | 22327 | 22473 | 24/2 |
| 30-rifle-combat | FULL | 1189637 | 450547 | 1/1 | 27019 | 28593 | 23034 | 23257 | 19/2 |
| 30-rifle-combat | TIMING_SEED | 1716836 | 530926 | 1/1 | 28808 | 30614 | 24819 | 26386 | 22/2 |
| 30-rifle-combat | FULL_WARM | 2042709 | 578499 | 1/1 | 31235 | — | — | 25813 | 24/2 |
| 30-rifle-combat | steady max | 2063012 | 598802 | 40/2 | 45356 | 47410 | 38304 | 39777 | 25/2 |
| 30-rifle-combat-small | FULL | 1189632 | 450542 | 1/1 | 26699 | 28223 | 22616 | 22762 | 19/2 |
| 30-rifle-combat-small | TIMING_SEED | 1716828 | 530918 | 1/1 | 28383 | 30229 | 24447 | 26025 | 22/2 |
| 30-rifle-combat-small | FULL_WARM | 2042705 | 578495 | 1/1 | 30870 | — | — | 25172 | 24/2 |
| 30-rifle-combat-small | steady max | 2063006 | 598796 | 40/2 | 44931 | 46960 | 37974 | 39379 | 25/2 |
| 10-mixed-idle | FULL | 333963 | 131563 | 2/0 | 10255 | 10774 | 9802 | 9822 | 6/1 |
| 10-mixed-idle | TIMING_SEED | 474222 | 153022 | 2/0 | 11437 | 10226 | 10588 | 10674 | 7/1 |
| 10-mixed-idle | FULL_WARM | 520971 | 160171 | 2/0 | 11669 | — | — | 10922 | 7/1 |
| 10-mixed-idle | steady max | 526504 | 165704 | 4/0 | 13725 | 12617 | 12682 | 12778 | 7/1 |
| 10-mixed-combat | FULL | 340219 | 132407 | 2/0 | 11984 | 12457 | 11610 | 11627 | 6/1 |
| 10-mixed-combat | TIMING_SEED | 499400 | 156618 | 2/0 | 13635 | 13973 | 13496 | 14799 | 7/1 |
| 10-mixed-combat | FULL_WARM | 571019 | 167077 | 2/0 | 14980 | — | — | 14334 | 7/1 |
| 10-mixed-combat | steady max | 577150 | 173208 | 4/0 | 20804 | 20421 | 20018 | 20620 | 8/1 |
| 20-mixed-idle | FULL | 740600 | 285200 | 2/2 | 21138 | 22219 | 18013 | 18031 | 12/1 |
| 20-mixed-idle | TIMING_SEED | 1056179 | 333479 | 2/2 | 22373 | 22173 | 18971 | 19052 | 14/1 |
| 20-mixed-idle | FULL_WARM | 1161368 | 349568 | 2/2 | 22994 | — | — | 19166 | 15/1 |
| 20-mixed-idle | steady max | 1173201 | 361401 | 80/4 | 25575 | 25446 | 22815 | 23009 | 15/2 |
| 20-mixed-combat | FULL | 746856 | 286011 | 2/2 | 24275 | 25214 | 22029 | 22046 | 12/1 |
| 20-mixed-combat | TIMING_SEED | 1081357 | 337042 | 2/2 | 26226 | 27475 | 24588 | 25938 | 14/2 |
| 20-mixed-combat | FULL_WARM | 1273976 | 365101 | 2/2 | 28076 | — | — | 26255 | 15/2 |
| 20-mixed-combat | steady max | 1287107 | 378232 | 80/4 | 39255 | 40740 | 36404 | 37674 | 16/2 |
| 30-mixed-idle | FULL | 1146713 | 438313 | 2/2 | 30868 | 32650 | 26361 | 26583 | 18/2 |
| 30-mixed-idle | TIMING_SEED | 1637612 | 513412 | 2/2 | 32293 | 32959 | 27159 | 27403 | 21/2 |
| 30-mixed-idle | FULL_WARM | 1801241 | 538441 | 2/2 | 33064 | — | — | 27310 | 22/2 |
| 30-mixed-idle | steady max | 1819374 | 556574 | 80/4 | 36520 | 37459 | 30430 | 30605 | 23/2 |
| 30-mixed-combat | FULL | 1152969 | 439091 | 2/2 | 35056 | 36784 | 31310 | 31494 | 18/2 |
| 30-mixed-combat | TIMING_SEED | 1662790 | 516942 | 2/2 | 37420 | 39634 | 33669 | 35005 | 22/2 |
| 30-mixed-combat | FULL_WARM | 1976409 | 562601 | 2/2 | 40463 | — | — | 35015 | 23/2 |
| 30-mixed-combat | steady max | 1996540 | 582732 | 80/4 | 55404 | 57529 | 51177 | 52760 | 24/3 |
| 30-diverse-idle | FULL | 1147251 | 438763 | 2/2 | 38654 | 40404 | 35222 | 35281 | 18/2 |
| 30-diverse-idle | TIMING_SEED | 1638150 | 513862 | 2/2 | 39724 | 40590 | 35440 | 35562 | 21/2 |
| 30-diverse-idle | FULL_WARM | 1801779 | 538891 | 2/2 | 40072 | — | — | 35507 | 22/2 |
| 30-diverse-idle | steady max | 1819912 | 557024 | 80/4 | 45218 | 46090 | 40531 | 40762 | 23/2 |
| 30-diverse-combat | FULL | 1153507 | 439585 | 2/2 | 42526 | 44219 | 39392 | 39664 | 18/2 |
| 30-diverse-combat | TIMING_SEED | 1663328 | 517425 | 2/2 | 45046 | 47347 | 42676 | 43787 | 22/2 |
| 30-diverse-combat | FULL_WARM | 1976947 | 563007 | 2/2 | 49513 | — | — | 46878 | 23/3 |
| 30-diverse-combat | steady max | 1997078 | 583138 | 80/4 | 64253 | 66712 | 61552 | 63056 | 24/3 |
| 30-mixed-combat-100 | FULL | 1177993 | 442555 | 2/2 | 35477 | 37232 | 32484 | 32680 | 19/2 |
| 30-mixed-combat-100 | TIMING_SEED | 1762886 | 530798 | 2/2 | 38808 | 43731 | 36850 | 41962 | 22/2 |
| 30-mixed-combat-100 | FULL_WARM | 1976409 | 562601 | 2/2 | 42135 | — | — | 37528 | 23/2 |
| 30-mixed-combat-100 | steady max | 1996540 | 582732 | 80/4 | 53464 | 57149 | 51064 | 56012 | 24/3 |
| 30-mixed-combat-200 | FULL | 1209273 | 446885 | 2/2 | 35637 | 37426 | 32854 | 33020 | 19/2 |
| 30-mixed-combat-200 | TIMING_SEED | 1812934 | 537726 | 2/2 | 38932 | 41560 | 34953 | 39263 | 22/2 |
| 30-mixed-combat-200 | FULL_WARM | 1976409 | 562601 | 2/2 | 41919 | — | — | 36857 | 23/2 |
| 30-mixed-combat-200 | steady max | 1996540 | 582732 | 80/4 | 51927 | 57060 | 50892 | 62221 | 24/3 |
| 30-mixed-combat-300 | FULL | 1240553 | 451215 | 2/2 | 35903 | 37739 | 32757 | 32909 | 19/2 |
| 30-mixed-combat-300 | TIMING_SEED | 1812934 | 537726 | 2/2 | 38828 | 41873 | 35582 | 42004 | 22/2 |
| 30-mixed-combat-300 | FULL_WARM | 1976409 | 562601 | 2/2 | 41812 | — | — | 37377 | 23/2 |
| 30-mixed-combat-300 | steady max | 1996540 | 582732 | 80/4 | 51857 | 57417 | 51402 | 65814 | 24/3 |

## Codec probes (steady means per observer per tick)

| Cohort | Obs | zlib-6 interned B | zlib-6 ms | zlib-1 interned B | zlib-1 ms | Capsules/tick | Capsules per-packet TCP B | One EVENT transaction: interned B / zlib B / TCP B | EVENT zlib ms |
|---|---|---|---|---|---|---|---|---|---|
| 10-rifle-idle | 0 | 4129 | 0.47 | 4915 | 0.16 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 10-rifle-combat | 0 | 5626 | 0.56 | 6720 | 0.22 | 0.90 | 1284 | 3732 / 1284 / 1347 | 0.086 |
| 20-rifle-idle | 0 | 7228 | 0.98 | 9283 | 0.36 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 20-rifle-combat | 0 | 10339 | 1.09 | 12676 | 0.40 | 0.95 | 1355 | 3940 / 1355 / 1422 | 0.090 |
| 30-rifle-idle | 0 | 10309 | 1.43 | 12685 | 0.50 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 30-rifle-combat | 0 | 14870 | 1.58 | 17944 | 0.57 | 0.95 | 1355 | 3940 / 1355 / 1421 | 0.088 |
| 30-rifle-combat-small | 0 | 14729 | 1.56 | 17857 | 0.57 | 0.95 | 1341 | 3940 / 1341 / 1400 | 0.089 |
| 10-mixed-idle | 0 | 5363 | 0.48 | 6275 | 0.18 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 10-mixed-idle | 1 | 5419 | 0.51 | 6454 | 0.18 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 10-mixed-combat | 0 | 7225 | 0.58 | 8340 | 0.21 | 0.97 | 950 | 2576 / 932 / 993 | 0.061 |
| 10-mixed-combat | 1 | 7343 | 0.62 | 8636 | 0.23 | 1.05 | 1021 | 2720 / 1015 / 1085 | 0.066 |
| 20-mixed-idle | 0 | 9862 | 1.00 | 12054 | 0.37 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 20-mixed-idle | 1 | 9919 | 1.05 | 12051 | 0.38 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 20-mixed-combat | 0 | 12975 | 1.17 | 15454 | 0.45 | 1.05 | 1369 | 4027 / 1345 / 1411 | 0.090 |
| 20-mixed-combat | 1 | 13107 | 1.23 | 15737 | 0.46 | 1.05 | 1378 | 4030 / 1372 / 1442 | 0.094 |
| 30-mixed-idle | 0 | 14463 | 1.54 | 18219 | 0.60 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 30-mixed-idle | 1 | 14515 | 1.57 | 18317 | 0.60 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 30-mixed-combat | 0 | 19075 | 1.71 | 23113 | 0.66 | 1.05 | 1369 | 4029 / 1345 / 1411 | 0.089 |
| 30-mixed-combat | 1 | 19201 | 1.79 | 23425 | 0.69 | 1.05 | 1378 | 4031 / 1372 / 1442 | 0.094 |
| 30-diverse-idle | 0 | 17043 | 1.77 | 20142 | 0.66 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 30-diverse-idle | 1 | 17300 | 1.80 | 20497 | 0.66 | 0.00 | 0 | 0 / 0 / 0 | 0.001 |
| 30-diverse-combat | 0 | 22606 | 2.16 | 26194 | 0.84 | 1.05 | 1422 | 4082 / 1398 / 1465 | 0.110 |
| 30-diverse-combat | 1 | 22966 | 2.12 | 26604 | 0.80 | 1.05 | 1432 | 4085 / 1426 / 1496 | 0.101 |
| 30-mixed-combat-100 | 0 | 19757 | 1.96 | 24554 | 0.76 | 4.92 | 4923 | 13146 / 2451 / 2522 | 0.203 |
| 30-mixed-combat-100 | 1 | 19785 | 1.90 | 24752 | 0.74 | 5.05 | 5020 | 13327 / 2438 / 2509 | 0.191 |
| 30-mixed-combat-200 | 0 | 19598 | 1.85 | 24331 | 0.72 | 9.75 | 7346 | 16734 / 2504 / 2574 | 0.208 |
| 30-mixed-combat-200 | 1 | 19628 | 1.90 | 24505 | 0.74 | 10.05 | 7558 | 17133 / 2493 / 2563 | 0.204 |
| 30-mixed-combat-300 | 0 | 19559 | 1.86 | 24339 | 0.73 | 14.57 | 9614 | 19885 / 2712 / 2780 | 0.224 |
| 30-mixed-combat-300 | 1 | 19635 | 1.85 | 24493 | 0.73 | 15.05 | 9919 | 20416 / 2708 / 2777 | 0.219 |

## Steady component partition, canonical → interned (bytes/tick, observer mean)

| Cohort | Records | Of which graphs | Decisions | Definitions | Seeds | Part-name strings |
|---|---|---|---|---|---|---|
| 10-rifle-idle | 204354 → 60804 | 139689 → 20889 | 40860 → 18585 | 3566 → 3566 | 17520 → 4402 | 4968 |
| 10-rifle-combat | 223839 → 63459 | 156618 → 22968 | 42777 → 19017 | 3566 → 3566 | 19392 → 4691 | 5589 |
| 20-rifle-idle | 431414 → 128364 | 294899 → 44099 | 86260 → 39235 | 7529 → 7529 | 36986 → 9294 | 10488 |
| 20-rifle-combat | 472549 → 133969 | 330638 → 48488 | 90307 → 40147 | 7529 → 7529 | 40315 → 9807 | 11799 |
| 30-rifle-idle | 658474 → 195924 | 450109 → 67309 | 131660 → 59885 | 11491 → 11491 | 56453 → 14185 | 16008 |
| 30-rifle-combat | 719310 → 204214 | 502965 → 73800 | 137645 → 61234 | 11491 → 11491 | 60822 → 14858 | 17947 |
| 30-rifle-combat-small | 719310 → 204214 | 502965 → 73800 | 137645 → 61234 | 11491 → 11491 | 60822 → 14858 | 17947 |
| 10-mixed-idle | 175614 → 55989 | 116871 → 17871 | 35439 → 16876 | 3414 → 3414 | 14888 → 3956 | 4140 |
| 10-mixed-combat | 192293 → 58262 | 131419 → 19670 | 37036 → 17230 | 3414 → 3414 | 16498 → 4205 | 4673 |
| 20-mixed-idle | 402674 → 123549 | 272081 → 41081 | 80839 → 37526 | 7305 → 7305 | 34354 → 8848 | 9660 |
| 20-mixed-combat | 441003 → 128757 | 305439 → 45188 | 84566 → 38352 | 7305 → 7305 | 37577 → 9344 | 10883 |
| 30-mixed-idle | 629734 → 191109 | 427291 → 64291 | 126239 → 58176 | 11171 → 11171 | 53821 → 13739 | 15180 |
| 30-mixed-combat | 688387 → 199071 | 478307 → 70563 | 131966 → 59443 | 11171 → 11171 | 58084 → 14395 | 17051 |
| 30-diverse-idle | 629734 → 191069 | 427291 → 64283 | 126239 → 58152 | 11197 → 11197 | 53821 → 13737 | 15180 |
| 30-diverse-combat | 688387 → 199018 | 478307 → 70552 | 131966 → 59411 | 11197 → 11197 | 58084 → 14392 | 17051 |
| 30-mixed-combat-100 | 689713 → 199252 | 479459 → 70704 | 132096 → 59472 | 11171 → 11171 | 59592 → 14627 | 17093 |
| 30-mixed-combat-200 | 689713 → 199252 | 479459 → 70704 | 132096 → 59472 | 11171 → 11171 | 59592 → 14627 | 17093 |
| 30-mixed-combat-300 | 689713 → 199252 | 479459 → 70704 | 132096 → 59472 | 11171 → 11171 | 59592 → 14627 | 17093 |

## Interning: steady handle/literal counts (whole cohort)

- 10-rifle-idle: handles {'ClipRef:generation': 6570, 'DefinitionRef:generation': 100278, 'FirstPersonRef:generation': 3546, 'MetadataRef:generation': 3546}; literals {'AttachmentRef:life': 1152, 'ConfigRef:life': 1188, 'EquipmentRef:life': 396, 'InputRef:life': 3024, 'ItemRef:life': 1170, 'SeedRef:life': 72}
- 10-rifle-combat: handles {'ClipRef:generation': 8910, 'DefinitionRef:generation': 112338, 'FirstPersonRef:generation': 4014, 'MetadataRef:generation': 4014}; literals {'AttachmentRef:life': 1152, 'ConfigRef:life': 1188, 'EquipmentRef:life': 396, 'InputRef:life': 3024, 'ItemRef:life': 1170, 'SeedRef:life': 108}
- 20-rifle-idle: handles {'ClipRef:generation': 13870, 'DefinitionRef:generation': 211698, 'FirstPersonRef:generation': 7486, 'MetadataRef:generation': 7486}; literals {'AttachmentRef:life': 2432, 'ConfigRef:life': 2508, 'EquipmentRef:life': 836, 'InputRef:life': 6384, 'ItemRef:life': 2470, 'SeedRef:life': 152}
- 20-rifle-combat: handles {'ClipRef:generation': 18318, 'DefinitionRef:generation': 235390, 'FirstPersonRef:generation': 8386, 'MetadataRef:generation': 8386}; literals {'AttachmentRef:life': 2432, 'ConfigRef:life': 2508, 'EquipmentRef:life': 836, 'InputRef:life': 6384, 'ItemRef:life': 2470, 'SeedRef:life': 190}
- 30-rifle-idle: handles {'ClipRef:generation': 21170, 'DefinitionRef:generation': 323118, 'FirstPersonRef:generation': 11426, 'MetadataRef:generation': 11426}; literals {'AttachmentRef:life': 3712, 'ConfigRef:life': 3828, 'EquipmentRef:life': 1276, 'InputRef:life': 9744, 'ItemRef:life': 3770, 'SeedRef:life': 232}
- 30-rifle-combat: handles {'ClipRef:generation': 27498, 'DefinitionRef:generation': 357178, 'FirstPersonRef:generation': 12710, 'MetadataRef:generation': 12710}; literals {'AttachmentRef:life': 3712, 'ConfigRef:life': 3828, 'EquipmentRef:life': 1276, 'InputRef:life': 9744, 'ItemRef:life': 3770, 'SeedRef:life': 270}
- 30-rifle-combat-small: handles {'ClipRef:generation': 27498, 'DefinitionRef:generation': 357178, 'FirstPersonRef:generation': 12710, 'MetadataRef:generation': 12710}; literals {'AttachmentRef:life': 3712, 'ConfigRef:life': 3828, 'EquipmentRef:life': 1276, 'InputRef:life': 9744, 'ItemRef:life': 3770, 'SeedRef:life': 270}
- 10-mixed-idle: handles {'ClipRef:generation': 10950, 'DefinitionRef:generation': 167130, 'FirstPersonRef:generation': 5910, 'MetadataRef:generation': 5910}; literals {'AttachmentRef:life': 2304, 'ConfigRef:life': 2376, 'EquipmentRef:life': 792, 'InputRef:life': 5670, 'ItemRef:life': 2334, 'SeedRef:life': 144}
- 10-mixed-combat: handles {'ClipRef:generation': 14738, 'DefinitionRef:generation': 187416, 'FirstPersonRef:generation': 6688, 'MetadataRef:generation': 6688}; literals {'AttachmentRef:life': 2304, 'ConfigRef:life': 2384, 'EquipmentRef:life': 792, 'InputRef:life': 5670, 'ItemRef:life': 2334, 'SeedRef:life': 225}
- 20-mixed-idle: handles {'ClipRef:generation': 25550, 'DefinitionRef:generation': 389970, 'FirstPersonRef:generation': 13790, 'MetadataRef:generation': 13790}; literals {'AttachmentRef:life': 4864, 'ConfigRef:life': 5016, 'EquipmentRef:life': 1672, 'InputRef:life': 12390, 'ItemRef:life': 4934, 'SeedRef:life': 304}
- 20-mixed-combat: handles {'ClipRef:generation': 33896, 'DefinitionRef:generation': 434652, 'FirstPersonRef:generation': 15492, 'MetadataRef:generation': 15492}; literals {'AttachmentRef:life': 4864, 'ConfigRef:life': 5024, 'EquipmentRef:life': 1672, 'InputRef:life': 12390, 'ItemRef:life': 4934, 'SeedRef:life': 388}
- 30-mixed-idle: handles {'ClipRef:generation': 40150, 'DefinitionRef:generation': 612810, 'FirstPersonRef:generation': 21670, 'MetadataRef:generation': 21670}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7656, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 464}
- 30-mixed-combat: handles {'ClipRef:generation': 52371, 'DefinitionRef:generation': 678849, 'FirstPersonRef:generation': 24163, 'MetadataRef:generation': 24163}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7664, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 548}
- 30-diverse-idle: handles {'ClipRef:generation': 40150, 'DefinitionRef:generation': 612810, 'FirstPersonRef:generation': 21670, 'MetadataRef:generation': 21670}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7656, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 464}
- 30-diverse-combat: handles {'ClipRef:generation': 52371, 'DefinitionRef:generation': 678849, 'FirstPersonRef:generation': 24163, 'MetadataRef:generation': 24163}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7664, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 548}
- 30-mixed-combat-100: handles {'ClipRef:generation': 54722, 'DefinitionRef:generation': 688164, 'FirstPersonRef:generation': 24592, 'MetadataRef:generation': 24592}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7664, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 863}
- 30-mixed-combat-200: handles {'ClipRef:generation': 54854, 'DefinitionRef:generation': 689346, 'FirstPersonRef:generation': 24614, 'MetadataRef:generation': 24614}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7664, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 1256}
- 30-mixed-combat-300: handles {'ClipRef:generation': 54854, 'DefinitionRef:generation': 690132, 'FirstPersonRef:generation': 24614, 'MetadataRef:generation': 24614}; literals {'AttachmentRef:life': 7424, 'ConfigRef:life': 7664, 'EquipmentRef:life': 2552, 'InputRef:life': 19110, 'ItemRef:life': 7534, 'SeedRef:life': 1649}

## Server-thread CPU per observer per steady tick (ms, thread CPU, phase23 reflective codec)

| Cohort | Obs | prepare mean / p50 (incl. its own size encode and folds) | of which closure walk | serialize | intern | SHA-256 | CRC32C | splice (reuse) | per-target rest (once per tick) | whole zlib canonical | whole zlib interned | stream RAW interned | TCP RAW interned per-fragment | TCP ZLIB-interned recompress |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10-rifle-idle | 0 | 22.00 / 2.98 | 0.78 | 1.69 | 2.94 | 0.11 | 0.015 | 0.25 | 1.79 | 1.33 | 0.47 | 0.59 | 0.69 | 0.08 |
| 10-rifle-combat | 0 | 29.35 / 2.72 | 0.69 | 1.67 | 2.15 | 0.12 | 0.016 | 0.25 | 1.65 | 1.46 | 0.56 | 0.86 | 0.74 | 0.10 |
| 20-rifle-idle | 0 | 43.37 / 5.40 | 1.29 | 3.93 | 4.12 | 0.24 | 0.029 | 0.52 | 3.52 | 2.72 | 0.98 | 1.17 | 1.42 | 0.12 |
| 20-rifle-combat | 0 | 51.69 / 5.50 | 1.31 | 3.52 | 3.46 | 0.25 | 0.027 | 0.49 | 3.26 | 2.92 | 1.09 | 1.50 | 1.49 | 0.16 |
| 30-rifle-idle | 0 | 62.55 / 7.93 | 1.88 | 5.47 | 5.15 | 0.35 | 0.035 | 0.74 | 5.02 | 3.92 | 1.43 | 1.70 | 2.05 | 0.16 |
| 30-rifle-combat | 0 | 72.65 / 7.91 | 1.94 | 5.25 | 4.69 | 0.38 | 0.036 | 0.76 | 4.85 | 4.29 | 1.58 | 2.08 | 2.23 | 0.22 |
| 30-rifle-combat-small | 0 | 73.83 / 7.87 | 1.94 | 5.15 | 4.54 | 0.38 | 0.035 | 0.77 | 4.87 | 4.33 | 1.56 | 2.06 | 2.17 | 0.21 |
| 10-mixed-idle | 0 | 16.10 / 2.39 | 0.63 | 1.35 | 2.22 | 0.10 | 0.013 | 0.20 | 1.43 | 1.21 | 0.48 | 0.62 | 0.68 | 0.10 |
| 10-mixed-idle | 1 | 19.70 / 2.64 | 0.65 | 1.40 | 1.98 | 0.10 | 0.014 | 0.27 | 0.26 | 1.32 | 0.51 | 0.64 | 0.72 | 0.10 |
| 10-mixed-combat | 0 | 22.20 / 2.41 | 0.62 | 1.37 | 1.80 | 0.10 | 0.014 | 0.21 | 1.40 | 1.42 | 0.58 | 0.88 | 0.78 | 0.12 |
| 10-mixed-combat | 1 | 24.36 / 2.70 | 0.64 | 1.45 | 1.81 | 0.11 | 0.014 | 0.22 | 0.30 | 1.61 | 0.62 | 0.91 | 0.81 | 0.12 |
| 20-mixed-idle | 0 | 38.55 / 4.94 | 1.25 | 3.24 | 3.20 | 0.22 | 0.024 | 0.44 | 2.96 | 2.61 | 1.00 | 1.28 | 1.39 | 0.15 |
| 20-mixed-idle | 1 | 40.43 / 5.50 | 1.40 | 3.23 | 3.48 | 0.23 | 0.028 | 0.49 | 0.31 | 2.83 | 1.05 | 1.33 | 1.45 | 0.16 |
| 20-mixed-combat | 0 | 49.08 / 5.15 | 1.26 | 3.45 | 3.09 | 0.23 | 0.022 | 0.45 | 3.07 | 3.08 | 1.17 | 1.62 | 1.54 | 0.20 |
| 20-mixed-combat | 1 | 51.60 / 5.46 | 1.40 | 3.44 | 3.37 | 0.25 | 0.025 | 0.49 | 0.32 | 3.29 | 1.23 | 1.69 | 1.60 | 0.20 |
| 30-mixed-idle | 0 | 59.20 / 7.83 | 1.94 | 5.49 | 4.78 | 0.34 | 0.037 | 0.75 | 5.13 | 4.18 | 1.54 | 1.90 | 2.14 | 0.22 |
| 30-mixed-idle | 1 | 60.94 / 7.49 | 2.00 | 5.18 | 4.64 | 0.35 | 0.042 | 0.78 | 0.35 | 4.32 | 1.57 | 1.97 | 2.17 | 0.21 |
| 30-mixed-combat | 0 | 71.61 / 7.99 | 1.96 | 5.43 | 4.78 | 0.36 | 0.034 | 0.74 | 5.06 | 4.71 | 1.71 | 2.36 | 2.28 | 0.29 |
| 30-mixed-combat | 1 | 72.54 / 7.62 | 2.03 | 5.42 | 4.75 | 0.38 | 0.039 | 0.76 | 0.34 | 5.02 | 1.79 | 2.39 | 2.38 | 0.29 |
| 30-diverse-idle | 0 | 64.12 / 8.22 | 2.02 | 5.60 | 6.48 | 0.35 | 0.039 | 0.79 | 5.35 | 4.65 | 1.77 | 2.12 | 2.28 | 0.29 |
| 30-diverse-idle | 1 | 65.11 / 7.81 | 1.99 | 5.14 | 5.42 | 0.36 | 0.039 | 0.79 | 0.36 | 4.78 | 1.80 | 2.21 | 2.35 | 0.29 |
| 30-diverse-combat | 0 | 80.36 / 8.37 | 2.17 | 5.90 | 7.08 | 0.40 | 0.059 | 0.94 | 5.47 | 5.69 | 2.16 | 2.92 | 2.63 | 0.44 |
| 30-diverse-combat | 1 | 89.37 / 8.27 | 2.07 | 5.15 | 5.84 | 0.39 | 0.057 | 0.87 | 0.35 | 5.53 | 2.12 | 2.75 | 2.58 | 0.43 |
| 30-mixed-combat-100 | 0 | 92.17 / 9.05 | 2.25 | 6.19 | 6.72 | 0.39 | 0.058 | 0.90 | 5.62 | 5.37 | 1.96 | 2.88 | 2.60 | 0.33 |
| 30-mixed-combat-100 | 1 | 93.64 / 8.44 | 2.16 | 5.27 | 5.39 | 0.39 | 0.058 | 0.86 | 0.36 | 5.33 | 1.90 | 2.76 | 2.51 | 0.31 |
| 30-mixed-combat-200 | 0 | 91.97 / 8.12 | 2.02 | 5.53 | 6.30 | 0.38 | 0.043 | 0.80 | 5.48 | 5.08 | 1.85 | 2.68 | 2.44 | 0.31 |
| 30-mixed-combat-200 | 1 | 91.10 / 7.88 | 2.08 | 5.53 | 5.62 | 0.40 | 0.048 | 0.82 | 0.39 | 5.36 | 1.90 | 2.80 | 2.50 | 0.32 |
| 30-mixed-combat-300 | 0 | 97.09 / 8.26 | 2.06 | 5.66 | 6.71 | 0.39 | 0.040 | 0.80 | 5.19 | 5.14 | 1.86 | 2.85 | 2.51 | 0.32 |
| 30-mixed-combat-300 | 1 | 100.14 / 7.81 | 2.02 | 5.02 | 5.40 | 0.40 | 0.042 | 0.80 | 0.35 | 5.24 | 1.85 | 2.82 | 2.51 | 0.32 |

Client per steady tick (ms): expand, decode (reflective), stream inflate:

- 10-rifle-idle: expand 0.46, decode 1.77, stream inflate 0.08, zlib inflate 0.09
- 10-rifle-combat: expand 0.49, decode 1.34, stream inflate 0.11, zlib inflate 0.09
- 20-rifle-idle: expand 1.01, decode 2.54, stream inflate 0.16, zlib inflate 0.15
- 20-rifle-combat: expand 0.96, decode 2.19, stream inflate 0.20, zlib inflate 0.16
- 30-rifle-idle: expand 1.39, decode 2.97, stream inflate 0.23, zlib inflate 0.21
- 30-rifle-combat: expand 1.42, decode 3.04, stream inflate 0.27, zlib inflate 0.22
- 30-rifle-combat-small: expand 1.41, decode 3.04, stream inflate 0.27, zlib inflate 0.21
- 10-mixed-idle: expand 0.46, decode 1.39, stream inflate 0.08, zlib inflate 0.09
- 10-mixed-combat: expand 0.48, decode 1.22, stream inflate 0.11, zlib inflate 0.10
- 20-mixed-idle: expand 0.99, decode 2.08, stream inflate 0.18, zlib inflate 0.15
- 20-mixed-combat: expand 1.03, decode 2.05, stream inflate 0.21, zlib inflate 0.16
- 30-mixed-idle: expand 1.52, decode 2.92, stream inflate 0.26, zlib inflate 0.21
- 30-mixed-combat: expand 1.56, decode 3.02, stream inflate 0.30, zlib inflate 0.24
- 30-diverse-idle: expand 1.67, decode 3.52, stream inflate 0.29, zlib inflate 0.24
- 30-diverse-combat: expand 1.83, decode 4.01, stream inflate 0.36, zlib inflate 0.28
- 30-mixed-combat-100: expand 1.62, decode 3.91, stream inflate 0.36, zlib inflate 0.27
- 30-mixed-combat-200: expand 1.55, decode 3.72, stream inflate 0.36, zlib inflate 0.26
- 30-mixed-combat-300: expand 1.57, decode 3.86, stream inflate 0.37, zlib inflate 0.26

## C2S (steady, production-scale varints unless `-small`)

| Cohort | Ticket B mean | Ticket max | Ticket zlib mean | Fragments max | Applied B/s raw | Applied B/s zlib | Probe S2C/C2S B |
|---|---|---|---|---|---|---|---|
| 10-rifle-idle | 4076 | 4088 | 750 | 1 | 2846 | 483 | 21/1 |
| 10-rifle-combat | 5166 | 5435 | 1575 | 1 | 5155 | 2439 | 21/1 |
| 20-rifle-idle | 8533 | 8558 | 1196 | 1 | 5839 | 693 | 21/1 |
| 20-rifle-combat | 10160 | 10567 | 2484 | 1 | 9190 | 3017 | 21/1 |
| 30-rifle-idle | 12990 | 13028 | 1627 | 1 | 8832 | 880 | 21/1 |
| 30-rifle-combat | 15061 | 15550 | 3159 | 1 | 12215 | 3181 | 21/1 |
| 30-rifle-combat-small | 11836 | 12627 | 2982 | 1 | 9814 | 2549 | 19/1 |
| 10-mixed-idle | 3535 | 3725 | 870 | 1 | 2838 | 540 | 21/1 |
| 10-mixed-combat | 4561 | 5132 | 1784 | 1 | 5655 | 2664 | 21/1 |
| 20-mixed-idle | 7994 | 8195 | 1317 | 1 | 5831 | 751 | 21/1 |
| 20-mixed-combat | 9635 | 10291 | 2575 | 1 | 9389 | 3141 | 21/1 |
| 30-mixed-idle | 12452 | 12665 | 1751 | 1 | 8824 | 933 | 21/1 |
| 30-mixed-combat | 14539 | 15241 | 3276 | 1 | 12375 | 3299 | 21/1 |
| 30-diverse-idle | 12452 | 12665 | 1751 | 1 | 8824 | 933 | 21/1 |
| 30-diverse-combat | 14539 | 15241 | 3276 | 1 | 12375 | 3299 | 21/1 |
| 30-mixed-combat-100 | 16624 | 18412 | 3548 | 1 | 20285 | 4143 | 21/1 |
| 30-mixed-combat-200 | 18458 | 21718 | 3300 | 1 | 34037 | 5282 | 21/1 |
| 30-mixed-combat-300 | 20229 | 25018 | 3387 | 2 | 47817 | 6319 | 21/1 |

## Head-of-line queue model (capsule completion latency after END tick, ms)

FIFO per observer link at a fixed rate; probe, capsules and snapshot fragments enqueued at END tick.

| Cohort | Path | Window | 2 Mbit/s p50/p95/max (snapshot max) | 5 Mbit/s p50/p95/max (snapshot max) | 10 Mbit/s p50/p95/max (snapshot max) | 25 Mbit/s p50/p95/max (snapshot max) |
|---|---|---|---|---|---|---|
| 10-rifle-idle | raknet-zlib | all | 0/0/0 (39) | 0/0/0 (16) | 0/0/0 (8) | 0/0/0 (3) |
| 10-rifle-idle | raknet-zlib | steady | 0/0/0 (39) | 0/0/0 (16) | 0/0/0 (8) | 0/0/0 (3) |
| 10-rifle-idle | tcp-zlib | all | 0/0/0 (39) | 0/0/0 (16) | 0/0/0 (8) | 0/0/0 (3) |
| 10-rifle-idle | tcp-zlib | steady | 0/0/0 (39) | 0/0/0 (16) | 0/0/0 (8) | 0/0/0 (3) |
| 10-rifle-idle | raknet-raw | all | 0/0/0 (38) | 0/0/0 (15) | 0/0/0 (8) | 0/0/0 (3) |
| 10-rifle-idle | raknet-raw | steady | 0/0/0 (38) | 0/0/0 (15) | 0/0/0 (8) | 0/0/0 (3) |
| 10-rifle-combat | raknet-zlib | all | 1/6/13 (63) | 0/0/2 (25) | 0/0/1 (13) | 0/0/0 (5) |
| 10-rifle-combat | raknet-zlib | steady | 1/6/13 (63) | 0/0/2 (25) | 0/0/1 (13) | 0/0/0 (5) |
| 10-rifle-combat | tcp-zlib | all | 6/6/24 (68) | 2/2/2 (27) | 1/1/1 (14) | 0/0/0 (5) |
| 10-rifle-combat | tcp-zlib | steady | 6/6/24 (68) | 2/2/2 (27) | 1/1/1 (14) | 0/0/0 (5) |
| 10-rifle-combat | raknet-raw | all | 5/5/26 (71) | 2/2/2 (28) | 1/1/1 (14) | 0/0/0 (6) |
| 10-rifle-combat | raknet-raw | steady | 5/5/26 (71) | 2/2/2 (28) | 1/1/1 (14) | 0/0/0 (6) |
| 20-rifle-idle | raknet-zlib | all | 0/0/0 (63) | 0/0/0 (25) | 0/0/0 (13) | 0/0/0 (5) |
| 20-rifle-idle | raknet-zlib | steady | 0/0/0 (63) | 0/0/0 (25) | 0/0/0 (13) | 0/0/0 (5) |
| 20-rifle-idle | tcp-zlib | all | 0/0/0 (63) | 0/0/0 (25) | 0/0/0 (13) | 0/0/0 (5) |
| 20-rifle-idle | tcp-zlib | steady | 0/0/0 (63) | 0/0/0 (25) | 0/0/0 (13) | 0/0/0 (5) |
| 20-rifle-idle | raknet-raw | all | 0/0/0 (76) | 0/0/0 (30) | 0/0/0 (15) | 0/0/0 (6) |
| 20-rifle-idle | raknet-raw | steady | 0/0/0 (76) | 0/0/0 (30) | 0/0/0 (15) | 0/0/0 (6) |
| 20-rifle-combat | raknet-zlib | all | 1/56/73 (118) | 0/2/2 (47) | 0/1/1 (24) | 0/0/0 (9) |
| 20-rifle-combat | raknet-zlib | steady | 1/56/73 (118) | 0/2/2 (47) | 0/1/1 (24) | 0/0/0 (9) |
| 20-rifle-combat | tcp-zlib | all | 6/68/81 (125) | 2/2/2 (50) | 1/1/1 (25) | 0/0/0 (10) |
| 20-rifle-combat | tcp-zlib | steady | 6/68/81 (125) | 2/2/2 (50) | 1/1/1 (25) | 0/0/0 (10) |
| 20-rifle-combat | raknet-raw | all | 5/83/94 (138) | 2/2/7 (55) | 1/1/1 (28) | 0/0/0 (11) |
| 20-rifle-combat | raknet-raw | steady | 5/83/94 (138) | 2/2/7 (55) | 1/1/1 (28) | 0/0/0 (11) |
| 30-rifle-idle | raknet-zlib | all | 0/0/0 (89) | 0/0/0 (36) | 0/0/0 (18) | 0/0/0 (7) |
| 30-rifle-idle | raknet-zlib | steady | 0/0/0 (89) | 0/0/0 (36) | 0/0/0 (18) | 0/0/0 (7) |
| 30-rifle-idle | tcp-zlib | all | 0/0/0 (90) | 0/0/0 (36) | 0/0/0 (18) | 0/0/0 (7) |
| 30-rifle-idle | tcp-zlib | steady | 0/0/0 (90) | 0/0/0 (36) | 0/0/0 (18) | 0/0/0 (7) |
| 30-rifle-idle | raknet-raw | all | 0/0/0 (113) | 0/0/0 (44) | 0/0/0 (22) | 0/0/0 (9) |
| 30-rifle-idle | raknet-raw | steady | 0/0/0 (113) | 0/0/0 (44) | 0/0/0 (22) | 0/0/0 (9) |
| 30-rifle-combat | raknet-zlib | all | 35/104/109 (153) | 0/2/14 (61) | 0/1/1 (31) | 0/0/0 (12) |
| 30-rifle-combat | raknet-zlib | steady | 35/104/109 (153) | 0/2/14 (61) | 0/1/1 (31) | 0/0/0 (12) |
| 30-rifle-combat | tcp-zlib | all | 193/297/298 (342) | 2/2/16 (64) | 1/1/1 (32) | 0/0/0 (13) |
| 30-rifle-combat | tcp-zlib | steady | 193/297/298 (342) | 2/2/16 (64) | 1/1/1 (32) | 0/0/0 (13) |
| 30-rifle-combat | raknet-raw | all | 387/678/690 (741) | 2/2/28 (76) | 1/1/1 (38) | 0/0/0 (15) |
| 30-rifle-combat | raknet-raw | steady | 387/678/690 (741) | 2/2/28 (76) | 1/1/1 (38) | 0/0/0 (15) |
| 30-rifle-combat-small | raknet-zlib | all | 28/102/108 (152) | 0/2/13 (61) | 0/1/1 (30) | 0/0/0 (12) |
| 30-rifle-combat-small | raknet-zlib | steady | 28/102/108 (152) | 0/2/13 (61) | 0/1/1 (30) | 0/0/0 (12) |
| 30-rifle-combat-small | tcp-zlib | all | 167/267/269 (313) | 2/2/15 (63) | 1/1/1 (32) | 0/0/0 (13) |
| 30-rifle-combat-small | tcp-zlib | steady | 167/267/269 (313) | 2/2/15 (63) | 1/1/1 (32) | 0/0/0 (13) |
| 30-rifle-combat-small | raknet-raw | all | 374/652/663 (714) | 2/2/27 (75) | 1/1/1 (38) | 0/0/0 (15) |
| 30-rifle-combat-small | raknet-raw | steady | 374/652/663 (714) | 2/2/27 (75) | 1/1/1 (38) | 0/0/0 (15) |
| 10-mixed-idle | raknet-zlib | all | 0/0/0 (51) | 0/0/0 (20) | 0/0/0 (10) | 0/0/0 (4) |
| 10-mixed-idle | raknet-zlib | steady | 0/0/0 (51) | 0/0/0 (20) | 0/0/0 (10) | 0/0/0 (4) |
| 10-mixed-idle | tcp-zlib | all | 0/0/0 (51) | 0/0/0 (20) | 0/0/0 (10) | 0/0/0 (4) |
| 10-mixed-idle | tcp-zlib | steady | 0/0/0 (51) | 0/0/0 (20) | 0/0/0 (10) | 0/0/0 (4) |
| 10-mixed-idle | raknet-raw | all | 0/0/0 (50) | 0/0/0 (20) | 0/0/0 (10) | 0/0/0 (4) |
| 10-mixed-idle | raknet-raw | steady | 0/0/0 (50) | 0/0/0 (20) | 0/0/0 (10) | 0/0/0 (4) |
| 10-mixed-combat | raknet-zlib | all | 1/7/32 (80) | 0/2/2 (32) | 0/1/1 (16) | 0/0/0 (6) |
| 10-mixed-combat | raknet-zlib | steady | 1/7/32 (80) | 0/2/2 (32) | 0/1/1 (16) | 0/0/0 (6) |
| 10-mixed-combat | tcp-zlib | all | 5/15/37 (82) | 2/2/4 (33) | 1/1/2 (16) | 0/0/1 (7) |
| 10-mixed-combat | tcp-zlib | steady | 5/15/37 (82) | 2/2/4 (33) | 1/1/2 (16) | 0/0/1 (7) |
| 10-mixed-combat | raknet-raw | all | 4/10/36 (82) | 2/2/4 (33) | 1/1/2 (16) | 0/0/1 (7) |
| 10-mixed-combat | raknet-raw | steady | 4/10/36 (82) | 2/2/4 (33) | 1/1/2 (16) | 0/0/1 (7) |
| 20-mixed-idle | raknet-zlib | all | 0/0/0 (91) | 0/0/0 (37) | 0/0/0 (18) | 0/0/0 (7) |
| 20-mixed-idle | raknet-zlib | steady | 0/0/0 (91) | 0/0/0 (37) | 0/0/0 (18) | 0/0/0 (7) |
| 20-mixed-idle | tcp-zlib | all | 0/0/0 (92) | 0/0/0 (37) | 0/0/0 (18) | 0/0/0 (7) |
| 20-mixed-idle | tcp-zlib | steady | 0/0/0 (92) | 0/0/0 (37) | 0/0/0 (18) | 0/0/0 (7) |
| 20-mixed-idle | raknet-raw | all | 0/0/0 (102) | 0/0/0 (41) | 0/0/0 (20) | 0/0/0 (8) |
| 20-mixed-idle | raknet-raw | steady | 0/0/0 (102) | 0/0/0 (41) | 0/0/0 (20) | 0/0/0 (8) |
| 20-mixed-combat | raknet-zlib | all | 43/96/101 (146) | 1/2/11 (58) | 0/1/1 (29) | 0/0/1 (12) |
| 20-mixed-combat | raknet-zlib | steady | 43/96/101 (146) | 1/2/11 (58) | 0/1/1 (29) | 0/0/1 (12) |
| 20-mixed-combat | tcp-zlib | all | 163/258/262 (306) | 2/3/13 (60) | 1/1/2 (30) | 0/1/1 (12) |
| 20-mixed-combat | tcp-zlib | steady | 163/258/262 (306) | 2/3/13 (60) | 1/1/2 (30) | 0/1/1 (12) |
| 20-mixed-combat | raknet-raw | all | 172/277/279 (324) | 2/2/17 (65) | 1/1/2 (33) | 0/0/1 (13) |
| 20-mixed-combat | raknet-raw | steady | 172/277/279 (324) | 2/2/17 (65) | 1/1/2 (33) | 0/0/1 (13) |
| 30-mixed-idle | raknet-zlib | all | 0/0/0 (148) | 0/0/0 (49) | 0/0/0 (24) | 0/0/0 (10) |
| 30-mixed-idle | raknet-zlib | steady | 0/0/0 (148) | 0/0/0 (49) | 0/0/0 (24) | 0/0/0 (10) |
| 30-mixed-idle | tcp-zlib | all | 0/0/0 (150) | 0/0/0 (49) | 0/0/0 (24) | 0/0/0 (10) |
| 30-mixed-idle | tcp-zlib | steady | 0/0/0 (150) | 0/0/0 (49) | 0/0/0 (24) | 0/0/0 (10) |
| 30-mixed-idle | raknet-raw | all | 0/0/0 (506) | 0/0/0 (60) | 0/0/0 (30) | 0/0/0 (12) |
| 30-mixed-idle | raknet-raw | steady | 0/0/0 (506) | 0/0/0 (60) | 0/0/0 (30) | 0/0/0 (12) |
| 30-mixed-combat | raknet-zlib | all | 633/1071/1117 (1176) | 1/8/34 (82) | 1/1/1 (41) | 0/0/1 (16) |
| 30-mixed-combat | raknet-zlib | steady | 633/1071/1117 (1176) | 1/8/34 (82) | 1/1/1 (41) | 0/0/1 (16) |
| 30-mixed-combat | tcp-zlib | all | 765/1342/1393 (1454) | 2/12/37 (84) | 1/1/2 (42) | 0/1/1 (17) |
| 30-mixed-combat | tcp-zlib | steady | 765/1342/1393 (1454) | 2/12/37 (84) | 1/1/2 (42) | 0/1/1 (17) |
| 30-mixed-combat | raknet-raw | all | 955/1705/1773 (1841) | 2/20/44 (92) | 1/1/2 (46) | 0/0/1 (18) |
| 30-mixed-combat | raknet-raw | steady | 955/1705/1773 (1841) | 2/20/44 (92) | 1/1/2 (46) | 0/0/1 (18) |
| 30-diverse-idle | raknet-zlib | all | 0/0/0 (492) | 0/0/0 (65) | 0/0/0 (32) | 0/0/0 (13) |
| 30-diverse-idle | raknet-zlib | steady | 0/0/0 (492) | 0/0/0 (65) | 0/0/0 (32) | 0/0/0 (13) |
| 30-diverse-idle | tcp-zlib | all | 0/0/0 (827) | 0/0/0 (65) | 0/0/0 (33) | 0/0/0 (13) |
| 30-diverse-idle | tcp-zlib | steady | 0/0/0 (827) | 0/0/0 (65) | 0/0/0 (33) | 0/0/0 (13) |
| 30-diverse-idle | raknet-raw | all | 0/0/0 (1220) | 0/0/0 (74) | 0/0/0 (37) | 0/0/0 (15) |
| 30-diverse-idle | raknet-raw | steady | 0/0/0 (1220) | 0/0/0 (74) | 0/0/0 (37) | 0/0/0 (15) |
| 30-diverse-combat | raknet-zlib | all | 1126/2037/2154 (2231) | 1/28/51 (98) | 0/1/1 (49) | 0/0/0 (20) |
| 30-diverse-combat | raknet-zlib | steady | 1126/2037/2154 (2231) | 1/28/51 (98) | 0/1/1 (49) | 0/0/0 (20) |
| 30-diverse-combat | tcp-zlib | all | 1267/2300/2437 (2515) | 2/32/53 (101) | 1/1/2 (50) | 0/1/1 (20) |
| 30-diverse-combat | tcp-zlib | steady | 1267/2300/2437 (2515) | 2/32/53 (101) | 1/1/2 (50) | 0/1/1 (20) |
| 30-diverse-combat | raknet-raw | all | 1397/2528/2679 (2761) | 2/39/59 (107) | 1/1/4 (53) | 0/0/1 (21) |
| 30-diverse-combat | raknet-raw | steady | 1397/2528/2679 (2761) | 2/39/59 (107) | 1/1/4 (53) | 0/0/1 (21) |
| 30-mixed-combat-100 | raknet-zlib | all | 806/1420/1488 (1552) | 1/14/36 (82) | 1/2/3 (41) | 0/1/1 (16) |
| 30-mixed-combat-100 | raknet-zlib | steady | 806/1420/1488 (1552) | 1/14/36 (82) | 1/2/3 (41) | 0/1/1 (16) |
| 30-mixed-combat-100 | tcp-zlib | all | 1267/2288/2415 (2480) | 5/29/51 (90) | 2/6/7 (45) | 1/2/3 (18) |
| 30-mixed-combat-100 | tcp-zlib | steady | 1267/2288/2415 (2480) | 5/29/51 (90) | 2/6/7 (45) | 1/2/3 (18) |
| 30-mixed-combat-100 | raknet-raw | all | 1192/2168/2251 (2325) | 3/26/45 (91) | 1/2/3 (46) | 1/1/1 (18) |
| 30-mixed-combat-100 | raknet-raw | steady | 1192/2168/2251 (2325) | 3/26/45 (91) | 1/2/3 (46) | 1/1/1 (18) |
| 30-mixed-combat-200 | raknet-zlib | all | 809/1445/1497 (1560) | 1/14/38 (81) | 1/2/3 (41) | 0/1/1 (16) |
| 30-mixed-combat-200 | raknet-zlib | steady | 809/1445/1497 (1560) | 1/14/38 (81) | 1/2/3 (41) | 0/1/1 (16) |
| 30-mixed-combat-200 | tcp-zlib | all | 1538/2824/3006 (3070) | 7/41/72 (100) | 3/9/11 (50) | 1/4/5 (20) |
| 30-mixed-combat-200 | tcp-zlib | steady | 1538/2824/3006 (3070) | 7/41/72 (100) | 3/9/11 (50) | 1/4/5 (20) |
| 30-mixed-combat-200 | raknet-raw | all | 1193/2166/2261 (2331) | 3/29/47 (91) | 1/3/3 (46) | 0/1/1 (18) |
| 30-mixed-combat-200 | raknet-raw | steady | 1193/2166/2261 (2331) | 3/29/47 (91) | 1/3/3 (46) | 0/1/1 (18) |
| 30-mixed-combat-300 | raknet-zlib | all | 837/1514/1558 (1620) | 2/14/39 (82) | 1/2/4 (41) | 0/1/1 (16) |
| 30-mixed-combat-300 | raknet-zlib | steady | 837/1514/1558 (1620) | 2/14/39 (82) | 1/2/4 (41) | 0/1/1 (16) |
| 30-mixed-combat-300 | tcp-zlib | all | 1814/3414/3584 (3647) | 11/60/84 (109) | 4/11/17 (53) | 2/5/6 (21) |
| 30-mixed-combat-300 | tcp-zlib | steady | 1814/3414/3584 (3647) | 11/60/84 (109) | 4/11/17 (53) | 2/5/6 (21) |
| 30-mixed-combat-300 | raknet-raw | all | 1230/2254/2325 (2396) | 3/28/48 (92) | 1/3/4 (46) | 1/1/1 (18) |
| 30-mixed-combat-300 | raknet-raw | steady | 1230/2254/2325 (2396) | 3/28/48 (92) | 1/3/4 (46) | 1/1/1 (18) |

## Aggregate uplink (fan-out arithmetic: N × mean per-observer steady rate)

| Cohort | RakNet ZLIB interned Mbit/s per obs | ×N Mbit/s | % of 300 | TCP ZLIB interned ×N | RakNet RAW interned ×N | No interning, RakNet RAW canonical ×N |
|---|---|---|---|---|---|---|
| 10-rifle-idle | 0.51 | 5.1 | 1.7% | 6.3 | 4.6 | 9.7 |
| 10-rifle-combat | 0.85 | 8.5 | 2.8% | 10.7 | 9.1 | 16.8 |
| 20-rifle-idle | 0.78 | 15.5 | 5.2% | 19.5 | 20.2 | 40.4 |
| 20-rifle-combat | 1.41 | 28.2 | 9.4% | 33.3 | 34.5 | 60.6 |
| 30-rifle-idle | 1.08 | 32.3 | 10.8% | 39.1 | 46.2 | 91.8 |
| 30-rifle-combat | 1.93 | 57.9 | 19.3% | 65.7 | 74.7 | 130.0 |
| 30-rifle-combat-small | 1.90 | 57.0 | 19.0% | 64.9 | 74.1 | 129.2 |
| 10-mixed-idle | 0.77 | 7.7 | 2.6% | 8.5 | 6.4 | 18.5 |
| 10-mixed-combat | 1.20 | 12.0 | 4.0% | 13.2 | 11.0 | 28.6 |
| 20-mixed-idle | 1.22 | 24.4 | 8.1% | 27.9 | 29.2 | 76.3 |
| 20-mixed-combat | 1.99 | 39.9 | 13.3% | 43.4 | 43.6 | 111.0 |
| 30-mixed-idle | 1.67 | 50.2 | 16.7% | 56.6 | 67.6 | 179.4 |
| 30-mixed-combat | 2.74 | 82.3 | 27.4% | 88.4 | 96.3 | 252.3 |
| 30-diverse-idle | 2.20 | 65.9 | 22.0% | 73.8 | 81.0 | 240.1 |
| 30-diverse-combat | 3.46 | 103.9 | 34.6% | 109.9 | 114.7 | 333.3 |
| 30-mixed-combat-100 | 3.01 | 90.4 | 30.1% | 109.2 | 106.0 | 271.9 |
| 30-mixed-combat-200 | 3.03 | 90.8 | 30.3% | 121.2 | 106.1 | 271.9 |
| 30-mixed-combat-300 | 3.07 | 92.0 | 30.7% | 133.0 | 107.5 | 273.4 |
