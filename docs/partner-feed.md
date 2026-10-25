# Partner feed format, version 1

Agencies, portals and their software vendors send their **full current inventory** as one JSON document, served over HTTPS or delivered as a file. Any listing missing from two consecutive feeds is marked as no longer for sale.

```json
{
  "feed_version": 1,
  "source": { "id": "agency-x", "name": "Agency X" },
  "listings": [
    {
      "ref": "AX-1042",
      "type": "land",
      "title": "Residential plot near the beach",
      "locality": "Pereybère",
      "lat": -20.0151, "lon": 57.5952,
      "price": { "amount": "5900000", "currency": "MUR" },
      "negotiable": false,
      "plot": { "value": 12, "unit": "perch" },
      "floor_m2": null,
      "bedrooms": null,
      "scheme": null,
      "floors_above_ground": null,
      "coastal": false,
      "agency": "Agency X",
      "url": "https://agency-x.mu/listings/1042",
      "contact_url": "https://agency-x.mu/listings/1042#contact",
      "images": ["https://agency-x.mu/img/1042-1.jpg"],
      "image_hashes": ["f0e1d2c3b4a59687"]
    }
  ]
}
```

| Field | Required | Notes |
|---|---|---|
| `ref` | yes | Stable ID in the partner's system. Must not change between feeds. |
| `type` | yes | `land`, `apartment` or `house`. |
| `title` | yes | Short title. Full descriptions stay on the partner's site. |
| `locality` | yes | Town or village. Unknown names are rejected and reported back, never guessed. |
| `lat`, `lon` | no | Exact coordinates. Without them the listing is placed at the village centre, which also makes automatic duplicate merging less likely. |
| `price` | yes | Amount as advertised, with `MUR`, `EUR` or `USD`. Stored in that currency. |
| `plot` | one of `plot`/`floor_m2` | Value plus `m2`, `perch`, `toise` or `arpent`. |
| `scheme` | no | `PDS`, `IRS`, `RES`, `SMART_CITY`, `IHS` or `G+2`. Drives the foreign-buyer answer. |
| `floors_above_ground` | for `G+2` | Without it a G+2 apartment shows "Not confirmed" for foreign buyers. |
| `images` | no | Used only when the partner agreement licenses them (`--images-licensed`). |
| `image_hashes` | no | 64-bit perceptual hashes (hex). Strongest signal for merging duplicates. |

Import with:

```bash
python -m mpf.cli ingest https://partner.example/feed.json --images-licensed
```
