### AUDIT-01 (ledger seq 8, commit 3283b6e)

| variant | band | mean diff USD/day | 95% CI | per 1mm DV01 traded | bp notional | Holm p | verdict |
|---|---|---:|---|---:|---:|---:|---|
| cover_integrated | low | 29,481 | -1,051 to 56,642 | 6,887 (-245 to 13,232) | 0.0543 | 1.0000 | inconclusive |
| fixed_p5 | low | -2,305,486 | -3,500,073 to -1,262,091 | -538,581 (-817,647 to -294,835) | -4.2447 | 0.6530 | inconclusive |
| slope_known | low | 147,212 | -1,399,147 to 1,273,308 | 34,390 (-326,853 to 297,455) | 0.2710 | 1.0000 | inconclusive |
| cover_integrated | central | 68,639 | -50,153 to 213,912 | 24,655 (-18,014 to 76,836) | 0.2105 | 1.0000 | inconclusive |
| fixed_p5 | central | -1,489,123 | -3,056,688 to 162,821 | -534,881 (-1,097,937 to 58,484) | -4.5672 | 1.0000 | inconclusive |
| slope_known | central | 0 | 0 to 0 | 0 (0 to 0) | 0.0000 | 1.0000 | inconclusive |
| cover_integrated | high | -4,668 | -41,201 to 22,708 | -4,516 (-39,863 to 21,970) | -0.0448 | 1.0000 | inconclusive |
| fixed_p5 | high | 504,094 | -1,297,238 to 2,652,988 | 487,715 (-1,255,087 to 2,566,785) | 4.8392 | 1.0000 | inconclusive |
| slope_known | high | 949,157 | 88,868 to 2,483,600 | 918,317 (85,981 to 2,402,901) | 9.1116 | 1.0000 | inconclusive |

| variant | band | mean P&L USD/day | 95% CI | daily std | hit rate | client DV01/day | spread capture | hedge cost | inventory | fees |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| cover_integrated | low | 3,481,619 | 2,354,837 to 5,423,412 | 3,575,413 | 0.285 | 4,274,836 | 3,416,626 | -1,053,554 | 1,189,331 | -67,427 |
| engine | low | 3,452,138 | 2,337,348 to 5,395,490 | 3,561,274 | 0.286 | 4,280,666 | 3,378,240 | -1,053,982 | 1,198,691 | -67,455 |
| fixed_p5 | low | 1,146,652 | -586,390 to 3,145,823 | 4,291,982 | 0.433 | 6,332,446 | 1,824,317 | -1,506,989 | 931,122 | -96,447 |
| slope_known | low | 3,599,351 | 2,562,332 to 4,944,697 | 2,802,647 | 0.185 | 2,836,840 | 3,595,016 | -703,833 | 754,741 | -45,045 |
| cover_integrated | central | 2,289,620 | 1,160,197 to 3,642,799 | 2,773,416 | 0.159 | 2,698,367 | 2,153,305 | -675,360 | 854,145 | -43,223 |
| engine | central | 2,220,981 | 1,068,251 to 3,619,597 | 2,822,776 | 0.161 | 2,784,028 | 2,189,103 | -704,402 | 780,609 | -45,082 |
| fixed_p5 | central | 731,858 | -957,865 to 2,781,125 | 4,280,040 | 0.394 | 6,347,114 | 1,839,939 | -1,567,389 | 565,432 | -100,313 |
| slope_known | central | 2,220,981 | 1,068,251 to 3,619,597 | 2,822,776 | 0.161 | 2,784,028 | 2,189,103 | -704,402 | 780,609 | -45,082 |
| cover_integrated | high | 962,449 | 211,963 to 1,832,896 | 2,160,653 | 0.038 | 1,000,377 | 797,308 | -269,194 | 451,859 | -17,228 |
| engine | high | 967,117 | 223,204 to 1,825,180 | 2,155,212 | 0.039 | 1,033,584 | 808,081 | -278,061 | 455,189 | -17,796 |
| fixed_p5 | high | 1,471,211 | -123,807 to 3,387,688 | 4,012,127 | 0.313 | 6,415,705 | 1,876,676 | -1,669,648 | 1,373,837 | -106,857 |
| slope_known | high | 1,916,274 | 739,669 to 3,602,097 | 3,369,406 | 0.124 | 2,760,100 | 1,508,972 | -728,272 | 1,182,314 | -46,609 |

Engine diagnostics (AUDIT-01), per day:

- low: by_size [{'dv01_per_day': 116339.4, 'mid_move_5min_dealer_bp_of_dv01': 0.0005, 'mid_move_5min_dealer_per_day': 60.9, 'size': '<10mm', 'spread_capture_per_day': 94066.14}, {'dv01_per_day': 613626.8, 'mid_move_5min_dealer_bp_of_dv01': -0.0689, 'mid_move_5min_dealer_per_day': -42248.29, 'size': '10-50mm', 'spread_capture_per_day': 489276.15}, {'dv01_per_day': 1234076.75, 'mid_move_5min_dealer_bp_of_dv01': -0.112, 'mid_move_5min_dealer_per_day': -138276.39, 'size': '50-200mm', 'spread_capture_per_day': 970935.66}, {'dv01_per_day': 2316622.9, 'mid_move_5min_dealer_bp_of_dv01': -0.1178, 'mid_move_5min_dealer_per_day': -272894.7, 'size': '>=200mm', 'spread_capture_per_day': 1823962.24}], by_tier [{'dv01_per_day': 2227127.5, 'markout_1min_per_day': 1706813.06, 'markout_5min_per_day': 1653404.99, 'mid_move_5min_dealer_bp_of_dv01': -0.0209, 'mid_move_5min_dealer_per_day': -46554.32, 'quoted': 3689, 'spread_bp_of_dv01': 0.7633, 'spread_capture_per_day': 1699959.31, 'tier': 1, 'won': 1108}, {'dv01_per_day': 1199621.0, 'markout_1min_per_day': 874209.47, 'markout_5min_per_day': 801260.62, 'mid_move_5min_dealer_bp_of_dv01': -0.1279, 'mid_move_5min_dealer_per_day': -153446.77, 'quoted': 2166, 'spread_bp_of_dv01': 0.7958, 'spread_capture_per_day': 954707.38, 'tier': 2, 'won': 597}, {'dv01_per_day': 853917.35, 'markout_1min_per_day': 624736.45, 'markout_5min_per_day': 470216.12, 'mid_move_5min_dealer_bp_of_dv01': -0.2967, 'mid_move_5min_dealer_per_day': -253357.39, 'quoted': 1491, 'spread_bp_of_dv01': 0.8474, 'spread_capture_per_day': 723573.5, 'tier': 3, 'won': 394}], decision_window_move_client_favour_per_day 23984.13, decision_window_move_positive_part_per_day 65839.58, decision_window_move_rms_usd 7065.15, event_window_dv01_per_day 65301.1, event_window_markout_5min_per_day 6016.16, event_window_spread_per_day 47005.23, event_window_won 40, lost_within_0p05bp_of_cover 3, mean_cover_gap_won_bp_dv01_weighted -0.476, money_left_on_table_per_day 2387.99
- central: by_size [{'dv01_per_day': 66741.55, 'mid_move_5min_dealer_bp_of_dv01': -0.0173, 'mid_move_5min_dealer_per_day': -1155.32, 'size': '<10mm', 'spread_capture_per_day': 54038.74}, {'dv01_per_day': 366030.55, 'mid_move_5min_dealer_bp_of_dv01': -0.0441, 'mid_move_5min_dealer_per_day': -16137.68, 'size': '10-50mm', 'spread_capture_per_day': 291430.6}, {'dv01_per_day': 746053.25, 'mid_move_5min_dealer_bp_of_dv01': -0.0436, 'mid_move_5min_dealer_per_day': -32499.84, 'size': '50-200mm', 'spread_capture_per_day': 587175.59}, {'dv01_per_day': 1605203.15, 'mid_move_5min_dealer_bp_of_dv01': -0.1944, 'mid_move_5min_dealer_per_day': -312121.59, 'size': '>=200mm', 'spread_capture_per_day': 1256458.42}], by_tier [{'dv01_per_day': 1535960.1, 'markout_1min_per_day': 1181532.55, 'markout_5min_per_day': 1146481.67, 'mid_move_5min_dealer_bp_of_dv01': -0.0211, 'mid_move_5min_dealer_per_day': -32392.91, 'quoted': 3689, 'spread_bp_of_dv01': 0.7675, 'spread_capture_per_day': 1178874.58, 'tier': 1, 'won': 630}, {'dv01_per_day': 672753.85, 'markout_1min_per_day': 457199.8, 'markout_5min_per_day': 376712.64, 'mid_move_5min_dealer_bp_of_dv01': -0.2214, 'mid_move_5min_dealer_per_day': -148920.65, 'quoted': 2166, 'spread_bp_of_dv01': 0.7813, 'spread_capture_per_day': 525633.29, 'tier': 2, 'won': 339}, {'dv01_per_day': 575314.55, 'markout_1min_per_day': 455802.93, 'markout_5min_per_day': 303994.6, 'mid_move_5min_dealer_bp_of_dv01': -0.3139, 'mid_move_5min_dealer_per_day': -180600.87, 'quoted': 1491, 'spread_bp_of_dv01': 0.8423, 'spread_capture_per_day': 484595.47, 'tier': 3, 'won': 211}], decision_window_move_client_favour_per_day 31652.57, decision_window_move_positive_part_per_day 50265.1, decision_window_move_rms_usd 8397.36, event_window_dv01_per_day 52603.5, event_window_markout_5min_per_day -825.79, event_window_spread_per_day 36707.88, event_window_won 23, lost_within_0p05bp_of_cover 3, mean_cover_gap_won_bp_dv01_weighted -0.4413, money_left_on_table_per_day 2387.99
- high: by_size [{'dv01_per_day': 15528.4, 'mid_move_5min_dealer_bp_of_dv01': 0.0501, 'mid_move_5min_dealer_per_day': 778.1, 'size': '<10mm', 'spread_capture_per_day': 12296.77}, {'dv01_per_day': 91641.9, 'mid_move_5min_dealer_bp_of_dv01': -0.018, 'mid_move_5min_dealer_per_day': -1652.79, 'size': '10-50mm', 'spread_capture_per_day': 72246.06}, {'dv01_per_day': 307283.45, 'mid_move_5min_dealer_bp_of_dv01': -0.0818, 'mid_move_5min_dealer_per_day': -25139.25, 'size': '50-200mm', 'spread_capture_per_day': 239153.06}, {'dv01_per_day': 619130.2, 'mid_move_5min_dealer_bp_of_dv01': -0.3193, 'mid_move_5min_dealer_per_day': -197686.04, 'size': '>=200mm', 'spread_capture_per_day': 484385.44}], by_tier [{'dv01_per_day': 574890.25, 'markout_1min_per_day': 416478.83, 'markout_5min_per_day': 377079.98, 'mid_move_5min_dealer_bp_of_dv01': -0.1009, 'mid_move_5min_dealer_per_day': -58001.66, 'quoted': 3689, 'spread_bp_of_dv01': 0.7568, 'spread_capture_per_day': 435081.64, 'tier': 1, 'won': 168}, {'dv01_per_day': 186083.0, 'markout_1min_per_day': 161288.93, 'markout_5min_per_day': 141414.21, 'mid_move_5min_dealer_bp_of_dv01': -0.0374, 'mid_move_5min_dealer_per_day': -6961.35, 'quoted': 2166, 'spread_bp_of_dv01': 0.7974, 'spread_capture_per_day': 148375.57, 'tier': 2, 'won': 80}, {'dv01_per_day': 272610.7, 'markout_1min_per_day': 199865.9, 'markout_5min_per_day': 65887.16, 'mid_move_5min_dealer_bp_of_dv01': -0.5823, 'mid_move_5min_dealer_per_day': -158736.96, 'quoted': 1491, 'spread_bp_of_dv01': 0.824, 'spread_capture_per_day': 224624.13, 'tier': 3, 'won': 36}], decision_window_move_client_favour_per_day 22111.51, decision_window_move_positive_part_per_day 27332.0, decision_window_move_rms_usd 14674.12, event_window_dv01_per_day 29351.85, event_window_markout_5min_per_day -29674.85, event_window_spread_per_day 18394.77, event_window_won 8, lost_within_0p05bp_of_cover 3, mean_cover_gap_won_bp_dv01_weighted -0.364, money_left_on_table_per_day 2387.99

### AUDIT-02 (ledger seq 9, commit 3283b6e)

| variant | band | mean diff USD/day | 95% CI | per 1mm DV01 traded | bp notional | Holm p | verdict |
|---|---|---:|---|---:|---:|---:|---|
| decline_tier3 | low | -789,527 | -1,651,814 to -156,196 | -184,440 (-385,878 to -36,489) | -1.4536 | 1.0000 | inconclusive |
| oracle_decline_informed | low | -1,132,315 | -2,149,096 to -357,587 | -264,518 (-502,047 to -83,535) | -2.0847 | 1.0000 | inconclusive |
| widen_tier3 | low | 93,101 | 59,307 to 129,610 | 21,749 (13,855 to 30,278) | 0.1714 | 1.0000 | inconclusive |
| decline_tier3 | central | -638,085 | -1,384,837 to -24,163 | -229,195 (-497,422 to -8,679) | -1.9570 | 1.0000 | inconclusive |
| oracle_decline_informed | central | -486,174 | -1,326,671 to 81,154 | -174,630 (-476,529 to 29,150) | -1.4911 | 1.0000 | inconclusive |
| widen_tier3 | central | -12,061 | -67,250 to 50,189 | -4,332 (-24,156 to 18,028) | -0.0370 | 1.0000 | inconclusive |
| decline_tier3 | high | -470,733 | -1,196,488 to 83,702 | -455,438 (-1,157,611 to 80,982) | -4.5189 | 1.0000 | inconclusive |
| oracle_decline_informed | high | -256,723 | -866,354 to 166,643 | -248,382 (-838,204 to 161,228) | -2.4645 | 1.0000 | inconclusive |
| widen_tier3 | high | -7,827 | -78,088 to 87,989 | -7,573 (-75,551 to 85,130) | -0.0751 | 1.0000 | inconclusive |

| variant | band | mean P&L USD/day | 95% CI | daily std | hit rate | client DV01/day | spread capture | hedge cost | inventory | fees |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| decline_tier3 | low | 2,662,611 | 1,451,999 to 4,543,808 | 3,387,350 | 0.291 | 3,426,748 | 2,654,667 | -851,774 | 915,878 | -54,514 |
| engine | low | 3,452,138 | 2,337,348 to 5,395,490 | 3,561,274 | 0.286 | 4,280,666 | 3,378,240 | -1,053,982 | 1,198,691 | -67,455 |
| oracle_decline_informed | low | 2,319,823 | 1,471,848 to 3,564,844 | 2,238,047 | 0.289 | 3,429,254 | 2,683,178 | -835,331 | 527,465 | -53,461 |
| widen_tier3 | low | 3,545,239 | 2,438,253 to 5,486,838 | 3,565,577 | 0.282 | 4,247,897 | 3,432,126 | -1,051,177 | 1,234,781 | -67,275 |
| decline_tier3 | central | 1,582,896 | 730,610 to 2,790,406 | 2,103,081 | 0.166 | 2,208,714 | 1,704,508 | -545,660 | 458,145 | -34,922 |
| engine | central | 2,220,981 | 1,068,251 to 3,619,597 | 2,822,776 | 0.161 | 2,784,028 | 2,189,103 | -704,402 | 780,609 | -45,082 |
| oracle_decline_informed | central | 1,734,807 | 882,568 to 2,871,458 | 2,262,267 | 0.163 | 2,272,896 | 1,776,143 | -549,925 | 542,701 | -35,195 |
| widen_tier3 | central | 2,208,920 | 1,062,693 to 3,602,524 | 2,833,448 | 0.154 | 2,709,214 | 2,174,425 | -688,813 | 766,777 | -44,084 |
| decline_tier3 | high | 496,383 | 36,662 to 782,902 | 1,175,055 | 0.042 | 760,973 | 583,457 | -201,592 | 127,599 | -12,902 |
| engine | high | 967,117 | 223,204 to 1,825,180 | 2,155,212 | 0.039 | 1,033,584 | 808,081 | -278,061 | 455,189 | -17,796 |
| oracle_decline_informed | high | 710,393 | 302,060 to 1,041,683 | 1,320,607 | 0.039 | 785,671 | 607,068 | -206,803 | 323,030 | -13,235 |
| widen_tier3 | high | 959,290 | 234,809 to 1,864,750 | 2,140,967 | 0.037 | 1,001,749 | 805,374 | -274,927 | 446,734 | -17,595 |

### AUDIT-03 (ledger seq 10, commit 3283b6e)

| variant | band | mean diff USD/day | 95% CI | per 1mm DV01 traded | bp notional | Holm p | verdict |
|---|---|---:|---|---:|---:|---:|---|
| bucketed | low | -44,806 | -340,116 to 159,018 | -10,467 (-79,454 to 37,148) | -0.0825 | 1.0000 | inconclusive |
| passive_ty | low | 245,717 | -753,589 to 1,157,610 | 57,401 (-176,045 to 270,427) | 0.4524 | 1.0000 | inconclusive |
| threshold_1m | low | -72,830 | -1,786,181 to 1,528,387 | -17,014 (-417,267 to 357,044) | -0.1341 | 1.0000 | inconclusive |
| threshold_250k | low | -66,310 | -467,383 to 323,432 | -15,491 (-109,185 to 75,556) | -0.1221 | 1.0000 | inconclusive |
| threshold_25k | low | -356,586 | -504,060 to -249,447 | -83,301 (-117,753 to -58,273) | -0.6565 | 1.0000 | inconclusive |
| bucketed | central | -80,099 | -308,379 to 142,331 | -28,771 (-110,767 to 51,124) | -0.2457 | 1.0000 | inconclusive |
| passive_ty | central | -122,913 | -1,101,889 to 841,034 | -44,149 (-395,789 to 302,092) | -0.3770 | 1.0000 | inconclusive |
| threshold_1m | central | -232,919 | -1,693,378 to 1,638,337 | -83,662 (-608,247 to 588,477) | -0.7144 | 1.0000 | inconclusive |
| threshold_250k | central | -333,923 | -546,240 to -60,877 | -119,943 (-196,205 to -21,866) | -1.0241 | 1.0000 | inconclusive |
| threshold_25k | central | -246,903 | -374,034 to -89,750 | -88,685 (-134,350 to -32,237) | -0.7573 | 1.0000 | inconclusive |
| bucketed | high | -2,863 | -125,540 to 123,346 | -2,770 (-121,461 to 119,338) | -0.0275 | 1.0000 | inconclusive |
| passive_ty | high | -429,290 | -1,333,640 to 283,712 | -415,341 (-1,290,306 to 274,494) | -4.1210 | 1.0000 | inconclusive |
| threshold_1m | high | -343,061 | -1,443,727 to 858,340 | -331,914 (-1,396,817 to 830,450) | -3.2933 | 1.0000 | inconclusive |
| threshold_250k | high | 214,001 | 24,298 to 446,079 | 207,048 (23,509 to 431,585) | 2.0543 | 1.0000 | inconclusive |
| threshold_25k | high | -95,406 | -165,985 to 20,614 | -92,306 (-160,592 to 19,944) | -0.9159 | 1.0000 | inconclusive |

| variant | band | mean P&L USD/day | 95% CI | daily std | hit rate | client DV01/day | spread capture | hedge cost | inventory | fees |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| bucketed | low | 3,407,332 | 2,249,725 to 5,391,308 | 3,561,524 | 0.286 | 4,280,666 | 3,378,240 | -1,036,563 | 1,140,795 | -61,790 |
| engine | low | 3,452,138 | 2,337,348 to 5,395,490 | 3,561,274 | 0.286 | 4,280,666 | 3,378,240 | -1,053,982 | 1,198,691 | -67,455 |
| passive_ty | low | 3,697,855 | 2,339,537 to 5,778,378 | 3,981,803 | 0.286 | 4,280,666 | 3,378,240 | 200,304 | 179,665 | -56,998 |
| threshold_1m | low | 3,379,308 | 1,671,082 to 5,073,546 | 3,989,401 | 0.286 | 4,280,666 | 3,378,240 | -351,983 | 378,934 | -22,527 |
| threshold_250k | low | 3,385,828 | 2,269,683 to 5,142,522 | 3,274,361 | 0.286 | 4,280,666 | 3,378,240 | -851,358 | 916,789 | -54,487 |
| threshold_25k | low | 3,095,553 | 1,971,436 to 5,031,077 | 3,460,773 | 0.286 | 4,280,666 | 3,378,240 | -1,276,791 | 1,079,174 | -81,715 |
| bucketed | central | 2,140,882 | 928,535 to 3,574,494 | 2,932,372 | 0.161 | 2,784,028 | 2,189,103 | -695,747 | 689,603 | -42,141 |
| engine | central | 2,220,981 | 1,068,251 to 3,619,597 | 2,822,776 | 0.161 | 2,784,028 | 2,189,103 | -704,402 | 780,609 | -45,082 |
| passive_ty | central | 2,098,069 | 1,031,783 to 3,482,972 | 2,684,281 | 0.161 | 2,784,028 | 2,189,103 | 130,437 | -184,805 | -37,420 |
| threshold_1m | central | 1,988,062 | 363,935 to 3,923,777 | 4,286,465 | 0.161 | 2,784,028 | 2,189,103 | -332,924 | 152,437 | -21,307 |
| threshold_250k | central | 1,887,058 | 842,846 to 3,179,615 | 2,780,036 | 0.161 | 2,784,028 | 2,189,103 | -575,142 | 309,153 | -36,809 |
| threshold_25k | central | 1,974,078 | 849,196 to 3,384,054 | 2,761,679 | 0.161 | 2,784,028 | 2,189,103 | -840,115 | 678,105 | -53,767 |
| bucketed | high | 964,253 | 194,282 to 1,863,018 | 2,283,173 | 0.039 | 1,033,584 | 808,081 | -263,456 | 437,658 | -16,448 |
| engine | high | 967,117 | 223,204 to 1,825,180 | 2,155,212 | 0.039 | 1,033,584 | 808,081 | -278,061 | 455,189 | -17,796 |
| passive_ty | high | 537,827 | -140,582 to 1,032,015 | 1,590,488 | 0.039 | 1,033,584 | 808,081 | 51,053 | -305,813 | -15,197 |
| threshold_1m | high | 624,055 | -516,704 to 1,789,849 | 2,392,384 | 0.039 | 1,033,584 | 808,081 | -108,025 | -68,791 | -6,914 |
| threshold_250k | high | 1,181,118 | 444,254 to 1,976,354 | 2,389,531 | 0.039 | 1,033,584 | 808,081 | -237,471 | 626,002 | -15,198 |
| threshold_25k | high | 871,711 | 152,401 to 1,737,793 | 2,135,975 | 0.039 | 1,033,584 | 808,081 | -318,786 | 403,115 | -20,402 |

### AUDIT-04 (ledger seq 11, commit 3283b6e)

| variant | band | mean diff USD/day | 95% CI | per 1mm DV01 traded | bp notional | Holm p | verdict |
|---|---|---:|---|---:|---:|---:|---|
| release_guard | low | 20,552 | -10,810 to 69,584 | 4,801 (-2,525 to 16,255) | 0.0378 | 1.0000 | inconclusive |
| skew_0p05 | low | 61,070 | -14,295 to 176,050 | 14,266 (-3,339 to 41,127) | 0.1124 | 1.0000 | inconclusive |
| skew_0p20 | low | 150,729 | 17,341 to 284,241 | 35,212 (4,051 to 66,401) | 0.2775 | 1.0000 | inconclusive |
| release_guard | central | 20,046 | -14,435 to 69,743 | 7,200 (-5,185 to 25,051) | 0.0615 | 1.0000 | inconclusive |
| skew_0p05 | central | 96,700 | -48,253 to 366,216 | 34,734 (-17,332 to 131,542) | 0.2966 | 1.0000 | inconclusive |
| skew_0p20 | central | -143,468 | -606,587 to 372,546 | -51,533 (-217,881 to 133,815) | -0.4400 | 1.0000 | inconclusive |
| release_guard | high | -3,245 | -18,693 to 6,654 | -3,139 (-18,086 to 6,438) | -0.0312 | 1.0000 | inconclusive |
| skew_0p05 | high | -35,003 | -276,202 to 126,679 | -33,866 (-267,228 to 122,563) | -0.3360 | 1.0000 | inconclusive |
| skew_0p20 | high | 5,451 | -281,536 to 195,753 | 5,274 (-272,389 to 189,392) | 0.0523 | 1.0000 | inconclusive |

| variant | band | mean P&L USD/day | 95% CI | daily std | hit rate | client DV01/day | spread capture | hedge cost | inventory | fees |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| engine | low | 3,452,138 | 2,337,348 to 5,395,490 | 3,561,274 | 0.286 | 4,280,666 | 3,378,240 | -1,053,982 | 1,198,691 | -67,455 |
| release_guard | low | 3,472,690 | 2,351,002 to 5,417,239 | 3,538,793 | 0.285 | 4,262,336 | 3,363,730 | -1,051,853 | 1,231,454 | -67,319 |
| skew_0p05 | low | 3,513,208 | 2,394,666 to 5,463,398 | 3,543,835 | 0.285 | 4,254,615 | 3,369,964 | -1,042,529 | 1,256,021 | -66,722 |
| skew_0p20 | low | 3,602,867 | 2,432,404 to 5,667,424 | 3,740,622 | 0.289 | 4,283,474 | 3,361,004 | -1,026,721 | 1,337,855 | -65,710 |
| engine | central | 2,220,981 | 1,068,251 to 3,619,597 | 2,822,776 | 0.161 | 2,784,028 | 2,189,103 | -704,402 | 780,609 | -45,082 |
| release_guard | central | 2,241,027 | 1,106,488 to 3,630,816 | 2,792,152 | 0.160 | 2,767,802 | 2,176,346 | -702,232 | 810,951 | -44,943 |
| skew_0p05 | central | 2,317,681 | 1,238,505 to 3,706,559 | 2,741,243 | 0.160 | 2,781,845 | 2,191,094 | -689,481 | 859,776 | -44,127 |
| skew_0p20 | central | 2,077,513 | 1,039,850 to 3,340,040 | 2,625,420 | 0.160 | 2,828,470 | 2,197,881 | -690,361 | 615,387 | -44,183 |
| engine | high | 967,117 | 223,204 to 1,825,180 | 2,155,212 | 0.039 | 1,033,584 | 808,081 | -278,061 | 455,189 | -17,796 |
| release_guard | high | 963,872 | 220,129 to 1,821,488 | 2,161,666 | 0.038 | 1,031,278 | 806,266 | -278,507 | 454,202 | -17,824 |
| skew_0p05 | high | 932,113 | 212,559 to 1,739,874 | 2,132,542 | 0.039 | 1,103,782 | 867,216 | -296,599 | 380,818 | -18,982 |
| skew_0p20 | high | 972,567 | 244,452 to 1,774,823 | 2,112,802 | 0.042 | 1,125,572 | 867,377 | -287,003 | 410,899 | -18,368 |

