"""Generate the bundled Kotlin station catalogue from the preserved OSM exports.

No network requests occur here. Corridor station order is reviewed separately;
OSM ref tags choose coordinates, with explicit overrides for station complexes.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
western = "CCG MEL CYR GTR MMCT MX PL PBHD DR MRU MM BA KHAR STC VLP ADH JOS RMAR GMN MDD KILE BVI DIC MIRA BYR NIG BSR NSP VR VTN SAH KLV PLG UOI BOR VGN DRD".split()
central = "CSMT MSD SNRD BY CHG CRD PR DR MTN SIN CLA VVH GC VK KJRD BND NHU MLND TNA KLVA MBQ DIVA KOPR DI THK KYN".split()
kasara = "SHAD ABY TLA KDV VSD ASO ATG THS KE OMB KSRA".split()
khopoli = "VLDI ULNR ABH BUD VGI SHLU NRL BVS KJT PDI KLY DLV LWJ KHPI".split()
harbour = "CSMT MSD SNRD DKRD RRD CTGN SVE VDLR GTBN CHF CLA TKNG CMBR GV MNKD VSH SNCR JNJ NEU SWDV BEPR KHAG MANR KNDS PNVL".split()
goregaon = "KCE MM BA KHAR STC VLP ADH JOS RMAR GMN".split()
trans = "TNA DIGH AIRL RABE GNSL KPHN TUH".split()
uran = "TRGHR BMDR KARP GAVAN RJNPD NHVSV DRNGR UNCT".split()
vasai = "BSR JCNR KARD KHBV BIRD KOPR DIVA DTVL NIIJ TPND NVRD KLMC PNVL SMNE RSYI APTA JITE HMRR PEN KASU NGTN NIDI ROHA".split()

CORRIDORS = [
    ("western", "Western", western),
    ("central-kasara", "Central · Kasara", central + kasara),
    ("central-khopoli", "Central · Karjat / Khopoli", central + khopoli),
    ("harbour-panvel", "Harbour · CSMT–Panvel", harbour),
    ("harbour-goregaon", "Harbour · CSMT–Goregaon", harbour[:8] + goregaon),
    ("harbour-panvel-goregaon", "Harbour · Panvel–Goregaon", list(reversed(harbour[7:])) + goregaon),
    ("trans-panvel", "Trans-Harbour · Thane–Panvel", trans + harbour[17:]),
    ("trans-vashi", "Trans-Harbour · Thane–Vashi", trans + ["SNCR", "VSH"]),
    ("port-nerul", "Uran / Port · Nerul–Uran", ["NEU", "SWDV"] + uran),
    ("port-belapur", "Uran / Port · Belapur–Uran", ["BEPR"] + uran),
    ("vasai-roha", "Vasai–Diva–Roha · MEMU", vasai),
]
OVERRIDES = {
    "PNVL": ("way", 113688843), "DR": ("node", 2630075003),
    "BEPR": ("node", 11335491946), "JOS": ("node", 12189363305),
    "NEU": ("way", 572630813), "VDLR": ("way", 1343435452),
    "KHAG": ("node", 12189351682), "CSMT": ("node", 12176277888),
    "CLA": ("node", 2663447640),
}
NAMES = {
    "CSMT": "CSMT", "DR": "Dadar", "STC": "Santacruz", "NSP": "Nalasopara",
    "KJRD": "Kanjurmarg", "NRL": "Neral", "KYN": "Kalyan", "VDLR": "Wadala Road",
    "CHF": "Chunabhatti", "SWDV": "Seawoods–Darave", "BEPR": "CBD Belapur",
    "PNVL": "Panvel", "JOS": "Jogeshwari", "BMDR": "Bamandongri",
    "KPHN": "Kopar Khairane", "JCNR": "Juchandra", "BIRD": "Bhiwandi Road",
    "KHBV": "Kharbao", "TPND": "Taloja Panchanand", "NVRD": "Navade Road",
    "RSYI": "Rasayani", "RJNPD": "Shematikhar", "UNCT": "Uran",
    "GAVAN": "Gavan", "TRGHR": "Targhar",
}
ALIASES = {
    "CSMT": "CST VT CSTM Mumbai Chhatrapati Shivaji Maharaj Terminus Victoria Terminus",
    "DR": "Dadar Central Western DDR", "PBHD": "Elphinstone Road",
    "MX": "Mahalakshmi", "NSP": "Nalla Sopara Nalasopara", "STC": "Santa Cruz",
    "KCE": "Kings Circle King Circle", "VDLR": "Vadala", "SWDV": "Seawoods Darave Karave",
    "BEPR": "Belapur", "KPHN": "Koparkhairane", "JCNR": "Juichandra",
    "HMRR": "Hamrapur Hamarapur", "RJNPD": "Ranjanpada", "GAVAN": "Gavhan Gavan",
    "UNCT": "Uran City", "BMDR": "Baman Dongri Bamandongari", "TPND": "Taloje Panchanand",
}

def generate():
    elements = json.loads((DATA / "mumbai-stations-osm.json").read_text(encoding="utf-8-sig"))["elements"]
    extra = DATA / "mumbai-extra-osm.json"
    if extra.exists():
        elements += json.loads(extra.read_text(encoding="utf-8-sig"))["elements"]
    ids = sorted(set(s for _, _, stations in CORRIDORS for s in stations))
    rows = []
    missing = []
    for station_id in ids:
        if station_id == "GAVAN":
            rows.append(dict(id=station_id, name="Gavan", lat=18.935465, lon=73.02215,
                             aliases="Gavhan Gavan GAVN", osm="wikidata/Q29026158"))
            continue
        if station_id in OVERRIDES:
            kind, feature_id = OVERRIDES[station_id]
            matches = [e for e in elements if e["type"] == kind and e["id"] == feature_id]
        else:
            matches = [e for e in elements if e.get("tags", {}).get("ref") == station_id
                       and e.get("tags", {}).get("station") not in ("subway", "monorail")]
        if not matches:
            missing.append(station_id)
            continue
        item = sorted(matches, key=lambda e: (e["type"] != "node", e["id"]))[0]
        coordinate = item if "lat" in item else item["center"]
        rows.append(dict(id=station_id, name=NAMES.get(station_id, item["tags"]["name"]),
                         lat=coordinate["lat"], lon=coordinate["lon"], aliases=ALIASES.get(station_id, ""),
                         osm=f'{item["type"]}/{item["id"]}'))
    if missing:
        raise SystemExit("Missing coordinate references: " + ", ".join(missing))
    output = ["package com.laststop.app", "", "// Generated by data/generate_network.py. OSM contributors (ODbL), Wikidata (CC0).",
              "object MumbaiNetworkData {", "    val stations = listOf("]
    for row in rows:
        values = [json.dumps(row["id"], ensure_ascii=False), json.dumps(row["name"], ensure_ascii=False),
                  str(row["lat"]), str(row["lon"]), json.dumps(row["aliases"], ensure_ascii=False)]
        output.append("        Station(" + ", ".join(values) + "),")
    output += ["    )", "    val corridors = listOf("]
    for corridor_id, name, station_ids in CORRIDORS:
        output.append(f'        Corridor({json.dumps(corridor_id)}, {json.dumps(name, ensure_ascii=False)}, "{" ".join(station_ids)}".split(" ")),')
    output += ["    )", "}", ""]
    target = ROOT / "app/src/main/java/com/laststop/app/MumbaiNetworkData.kt"
    target.write_text("\n".join(output), encoding="utf-8")
    (DATA / "mumbai-catalogue.json").write_text(json.dumps(dict(stations=rows, corridors=CORRIDORS), indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Generated {len(rows)} stations and {len(CORRIDORS)} corridors")

if __name__ == "__main__":
    generate()
