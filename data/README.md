# Offline network data

The version-0.2 catalogue contains **142 unique stations** and 11 corridor variants across the Western, Central, Harbour, Trans-Harbour, Uran/Port, and Vasai–Diva–Roha networks. Shared interchanges use stable station IDs. Dadar's Central/Western complexes are represented as one interchange; the selected coordinate is on the Central side, within the same walking interchange. Jogeshwari's Western position is used for the shared station, so Harbour platforms may be several hundred metres away.

## Reproducible catalogue

- `mumbai-stations-osm.json`: preserved Overpass export of railway=station features within the Mumbai-region bounding box. It also contains unrelated metro/Pune features; these are not automatically added to the app.
- `mumbai-extra-osm.json`: railway halt/construction/proposed feature export, needed for the **operational Rasayani and Navade Road** halts. Construction/proposed features are not included automatically.
- `generate_network.py`: explicit reviewed corridor sequences, station-name aliases, feature overrides, and deterministic Kotlin generator. Run `python data/generate_network.py` to regenerate the app's `MumbaiNetworkData.kt` and the provenance catalogue.
- `mumbai-catalogue.json`: selected station coordinates and OSM feature IDs, together with the exact corridor sequences.
- `stations-osm.json`: retained original version-0.1 Panvel–Vashi export.

All stations except Gavan use the OSM feature with their railway reference, or an explicit station-complex override. Polygon stations use the Overpass bounding-box centre. Panvel uses the suburban complex; CBD Belapur excludes the nearby metro station. Gavan was absent from the retrieved OSM station/halts; its coordinate is explicitly sourced from [Wikidata Q29026158](https://www.wikidata.org/wiki/Q29026158), 18°56′7.674″N, 73°1′19.740″E. Gavan/Gavhan/GAVN are searchable aliases.

The app includes Digha Gaon, Targhar and Gavan. The upcoming Panvel–Karjat suburban extension is not assumed operational. Existing passenger/MEMU stations on Vasai–Diva–Roha are included; this is not a promise of a single direct through train.

## Order and coverage references

- [Central Railway system map](https://cr.indianrailways.gov.in/uploads/files/1704181457106-Survey_Map_02.01.2024.pdf)
- [Central Railway Harbour timetable](https://cr.indianrailways.gov.in/cris/uploads/files/1640850838696-3.%20DN%20Harbour.pdf)
- [Indian Railways station directory](https://indianrailways.gov.in/Railway_station_zone-category_wise_list.pdf)
- [Western-line station sequence](https://en.wikipedia.org/wiki/Western_line_%28Mumbai_Suburban_Railway%29)
- [Uran/Port-line station sequence](https://en.wikipedia.org/wiki/Port_line_%28Mumbai_Suburban_Railway%29)
- [Gavan station and its December 2025 opening](https://en.wikipedia.org/wiki/Gavan_railway_station)

These references establish station identity/order; times are never used to trigger the alarm. Offline data can become outdated. A corridor is not a timetable or a statement that a direct service exists for every station pair. Users must confirm their actual train route and stops.

The planner orders source-to-destination station paths in either direction, prefers fewer corridor changes, and then shorter geographic distance. Interchange journeys wake near the station before each leg endpoint and require an explicit start for the next leg. For a next-station leg, the wake-up station is the source and the app warns the user to stay awake. Metro, monorail, the Neral–Matheran toy railway, and future/unconfirmed extensions are outside this catalogue.

## Accuracy and licensing

Station centres are joined by straight segments. Curves, platforms, separate tracks, stopping patterns and temporary closures are not represented. The 800 m tolerance is a coarse route check, not proof that the user is on a particular track. Field verification remains necessary, particularly for long rural segments and new corridors.

OSM-derived coordinates: **© OpenStreetMap contributors**, [ODbL 1.0](https://www.openstreetmap.org/copyright). Gavan coordinate: Wikidata, [CC0](https://www.wikidata.org/wiki/Wikidata:Licensing). The generated catalogue, preserved exports and in-app attribution retain these sources. No map tiles are bundled or requested.
