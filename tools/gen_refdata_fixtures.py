"""Generate data/refdata/v1 fixtures. Identifiers are synthetic with valid check digits."""

import hashlib
import sys
from pathlib import Path

root = Path(sys.argv[1]) / "data" / "refdata" / "v1"
root.mkdir(parents=True, exist_ok=True)


def cusip_check(base8: str) -> str:
    total = 0
    for i, ch in enumerate(base8):
        if ch.isdigit():
            v = int(ch)
        elif ch.isalpha():
            v = ord(ch.upper()) - ord("A") + 10
        else:
            v = {"*": 36, "@": 37, "#": 38}[ch]
        if i % 2 == 1:
            v *= 2
        total += v // 10 + v % 10
    return str((10 - total % 10) % 10)


def isin_check(base11: str) -> str:
    digits = "".join(str(int(c, 36)) for c in base11)
    total = 0
    for i, ch in enumerate(reversed(digits)):
        v = int(ch)
        if i % 2 == 0:
            v *= 2
        total += v // 10 + v % 10
    return str((10 - total % 10) % 10)


def us(base8: str) -> tuple[str, str]:
    cusip = base8 + cusip_check(base8)
    isin = "US" + cusip
    return isin + isin_check(isin), cusip



import calendar as _cal


def first_coupon(mat: str, iss: str, freq: int) -> str:
    """Step back from maturity by 12/freq months (end-of-month rule) to the first date after issue."""
    y, m, d = (int(x) for x in mat.split("-"))
    eom = d == _cal.monthrange(y, m)[1]
    step = 12 // freq
    best = mat
    while True:
        m -= step
        while m < 1:
            m += 12
            y -= 1
        dd = _cal.monthrange(y, m)[1] if eom else min(d, _cal.monthrange(y, m)[1])
        cand = f"{y:04d}-{m:02d}-{dd:02d}"
        if cand <= iss:
            return best
        best = cand

bonds = []
auctions = []
# UST: (tenorKey, seq letters, coupon, maturity, announce, auction, issue)
ust = [
    ("2Y", "ZA1", "3.625", "2028-08-31", "2026-08-20", "2026-08-25", "2026-08-31"),
    ("2Y", "ZB2", "3.500", "2028-09-30", "2026-09-17", "2026-09-22", "2026-09-30"),
    ("2Y", "ZC3", "3.375", "2028-10-31", "2026-10-22", "2026-10-27", "2026-11-02"),
    ("2Y", "ZD4", "3.375", "2028-11-30", "2026-11-19", "2026-11-23", "2026-11-30"),
    ("5Y", "ZE5", "3.750", "2031-09-30", "2026-09-17", "2026-09-23", "2026-09-30"),
    ("5Y", "ZF6", "3.625", "2031-10-31", "2026-10-22", "2026-10-28", "2026-11-02"),
    ("10Y", "ZG7", "4.125", "2036-08-15", "2026-08-05", "2026-08-12", "2026-08-15"),
    ("10Y", "ZH8", "4.000", "2036-11-15", "2026-11-04", "2026-11-10", "2026-11-16"),
    ("30Y", "ZJ9", "4.625", "2056-08-15", "2026-08-05", "2026-08-13", "2026-08-15"),
    ("30Y", "ZK1", "4.500", "2056-11-15", "2026-11-04", "2026-11-12", "2026-11-16"),
]
bond_id = 1001
for tenor, letters, cpn, mat, ann, auc, iss in ust:
    isin, cusip = us("91282C" + letters[:2])
    first_cpn = first_coupon(mat, iss, 2)
    bonds.append(
        [bond_id, isin, cusip, "UST", "USD", tenor, cpn, "2", mat, first_cpn, iss, iss, "US-GS", ann, auc, iss]
    )
    auctions.append(["UST", tenor, isin, ann, auc, iss])
    bond_id += 1


def gb(base9: str) -> tuple[str, str]:
    b = "GB00" + base9[:7]
    return b + isin_check(b), ""


def de(base9: str) -> tuple[str, str]:
    b = "DE000" + base9[:6]
    return b + isin_check(b), ""


gilts = [
    ("10Y", "BZ1RT01", "4.250", "2036-07-31", "2026-08-28", "2026-09-09", "2026-09-10"),
    ("10Y", "BZ1RT02", "4.375", "2037-01-31", "2026-10-30", "2026-11-11", "2026-11-12"),
]
for tenor, base, cpn, mat, ann, auc, iss in gilts:
    isin, _ = gb(base)
    bonds.append([bond_id, isin, "", "GILT", "GBP", tenor, cpn, "2", mat, first_coupon(mat, iss, 2), iss, iss, "UK", ann, auc, iss])
    auctions.append(["GILT", tenor, isin, ann, auc, iss])
    bond_id += 1
bunds = [
    ("10Y", "BU2Z01", "2.600", "2036-08-15", "2026-06-26", "2026-07-08", "2026-07-10"),
    ("10Y", "BU2Z02", "2.700", "2037-02-15", "2026-12-28", "2027-01-13", "2027-01-15"),
]
for tenor, base, cpn, mat, ann, auc, iss in bunds:
    isin, _ = de(base)
    first = first_coupon(mat, iss, 1)
    bonds.append([bond_id, isin, "", "BUND", "EUR", tenor, cpn, "1", mat, first, iss, iss, "TARGET", ann, auc, iss])
    auctions.append(["BUND", tenor, isin, ann, auc, iss])
    bond_id += 1

files = {}
files["bonds.csv"] = (
    "# Securities master: government bonds. Identifiers are synthetic (valid check digits), not real issues.\n"
    "# coupon in percent; frequency = coupons per year; dates ISO; calendar names a CalendarSource calendar.\n"
    "id,isin,cusip,market,currency,tenor,coupon,frequency,maturity,firstCouponDate,issueDate,firstSettlementDate,calendar,announcementDate,auctionDate,onTheRunFromDate\n"
    + "".join(",".join(str(x) for x in b) + "\n" for b in bonds)
)
files["auctions.csv"] = (
    "# Auction calendar fixture for Treasuries, Gilts and Bunds (synthetic dates in the issuers' usual pattern).\n"
    "market,tenor,isin,announcementDate,auctionDate,issueDate\n"
    + "".join(",".join(a) + "\n" for a in auctions)
)
files["swap_templates.csv"] = (
    "# Swap templates per market. blockThreshold in currency units of notional; madeAvailableToTrade marks CFTC MAT tenors.\n"
    "id,market,currency,index,tenor,fixedFrequency,fixedDayCount,floatFrequency,floatDayCount,paymentLagDays,calendar,spotLagDays,madeAvailableToTrade,blockThreshold\n"
    "2001,USD-SOFR,USD,SOFR,2Y,1,ACT/360,1,ACT/360,2,US-GS,2,true,440000000\n"
    "2002,USD-SOFR,USD,SOFR,5Y,1,ACT/360,1,ACT/360,2,US-GS,2,true,300000000\n"
    "2003,USD-SOFR,USD,SOFR,10Y,1,ACT/360,1,ACT/360,2,US-GS,2,true,170000000\n"
    "2004,USD-SOFR,USD,SOFR,30Y,1,ACT/360,1,ACT/360,2,US-GS,2,true,110000000\n"
    "2005,USD-SOFR,USD,SOFR,12Y,1,ACT/360,1,ACT/360,2,US-GS,2,false,170000000\n"
    "2006,EUR-ESTR,EUR,ESTR,10Y,1,ACT/360,1,ACT/360,1,TARGET,2,false,150000000\n"
    "2007,GBP-SONIA,GBP,SONIA,10Y,1,ACT/365F,1,ACT/365F,0,UK,0,false,120000000\n"
)
files["futures.csv"] = (
    "# Futures contract specifications (CBOT Treasury futures and Eurex Bund), from the exchanges' published specifications.\n"
    "id,symbol,exchange,underlying,currency,contractSize,tickSize,notionalCoupon,deliveryMonth,firstDeliveryDate,lastTradingDate\n"
    "3001,TUZ6,CBOT,UST-2Y,USD,200000,0.0078125,6,2026-12,2026-12-01,2026-12-31\n"
    "3002,FVZ6,CBOT,UST-5Y,USD,100000,0.0078125,6,2026-12,2026-12-01,2026-12-31\n"
    "3003,TYZ6,CBOT,UST-10Y,USD,100000,0.015625,6,2026-12,2026-12-01,2026-12-21\n"
    "3004,USZ6,CBOT,UST-BOND,USD,100000,0.03125,6,2026-12,2026-12-01,2026-12-21\n"
    "3005,FGBLZ6,EUREX,BUND-10Y,EUR,100000,0.01,6,2026-12,2026-12-10,2026-12-08\n"
)
files["parties.csv"] = (
    "# Party master: clients with tiers, dealers, venues and FCMs. Names are invented.\n"
    "id,code,name,type,tier\n"
    "1,RALGO,rates-algo-trading desk,DEALER,0\n"
    "11,DLR-A,Simulated Dealer A,DEALER,0\n"
    "12,DLR-B,Simulated Dealer B,DEALER,0\n"
    "13,DLR-C,Simulated Dealer C,DEALER,0\n"
    "14,DLR-D,Simulated Dealer D,DEALER,0\n"
    "15,DLR-E,Simulated Dealer E,DEALER,0\n"
    "101,D2C-RFQ,Simulated D2C RFQ venue,VENUE,0\n"
    "102,D2D-CLOB,Simulated D2D CLOB venue,VENUE,0\n"
    "103,SEF,Simulated swap execution facility,VENUE,0\n"
    "201,FCM-1,Simulated FCM One,FCM,0\n"
    "202,FCM-2,Simulated FCM Two,FCM,0\n"
    "2001,CL-PENSION,Client pension fund,CLIENT,1\n"
    "2002,CL-INSURER,Client insurer,CLIENT,1\n"
    "2003,CL-ASSETMGR,Client asset manager,CLIENT,2\n"
    "2004,CL-HEDGEFUND,Client hedge fund,CLIENT,2\n"
    "2005,CL-REGIONAL,Client regional bank,CLIENT,3\n"
)
hol = {
    "US-GS": [
        ("2026-01-01", "New Year"), ("2026-01-19", "Martin Luther King Jr Day"), ("2026-02-16", "Presidents Day"),
        ("2026-04-03", "Good Friday"), ("2026-05-25", "Memorial Day"), ("2026-06-19", "Juneteenth"),
        ("2026-07-03", "Independence Day observed"), ("2026-09-07", "Labor Day"), ("2026-10-12", "Columbus Day"),
        ("2026-11-11", "Veterans Day"), ("2026-11-26", "Thanksgiving"), ("2026-12-25", "Christmas"),
        ("2027-01-01", "New Year"),
    ],
    "UK": [
        ("2026-01-01", "New Year"), ("2026-04-03", "Good Friday"), ("2026-04-06", "Easter Monday"),
        ("2026-05-04", "Early May bank holiday"), ("2026-05-25", "Spring bank holiday"),
        ("2026-08-31", "Summer bank holiday"), ("2026-12-25", "Christmas"), ("2026-12-28", "Boxing Day observed"),
        ("2027-01-01", "New Year"),
    ],
    "TARGET": [
        ("2026-01-01", "New Year"), ("2026-04-03", "Good Friday"), ("2026-04-06", "Easter Monday"),
        ("2026-05-01", "Labour Day"), ("2026-12-25", "Christmas"), ("2026-12-26", "St Stephen"),
        ("2027-01-01", "New Year"),
    ],
}
files["holidays.csv"] = (
    "# Holiday fixture for 2026 (and 1 January 2027) for the calendars the venues use. Replaced by rates-analytics calendars once merged.\n"
    "calendar,date,name\n"
    + "".join(f"{c},{d},{n}\n" for c, rows in hol.items() for d, n in rows)
)
for name, text in files.items():
    (root / name).write_bytes(text.encode("utf-8"))
lines = ["# rates-algo-trading reference data fixtures, version 1. sha256 of each file; the loader refuses a mismatch.\n"]
for name in sorted(files):
    lines.append(f"{hashlib.sha256((root / name).read_bytes()).hexdigest()}  {name}\n")
(root / "MANIFEST.sha256").write_bytes("".join(lines).encode("utf-8"))
print("\n".join(sorted(files)))
