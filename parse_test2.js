const gpxText = '<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd" version="1.1" creator="Mapy.cz"><trk><name>Trasa</name></trk></gpx>';
let wptXml = '  <wpt lat="32" lon="35"><name>1</name></wpt>';
let modified = gpxText;
const gpxTagEnd = modified.indexOf('>', modified.indexOf('<gpx')) + 1;
if (gpxTagEnd > 0) {
    modified = modified.substring(0, gpxTagEnd) + '\n' + wptXml + modified.substring(gpxTagEnd);
}
console.log(modified);
