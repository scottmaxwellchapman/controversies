<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.Year" %>
<%@ page import="java.time.ZoneId" %>

<%!
  private static String footerEsc(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }
%>

<%
  long serverEpochMs = Instant.now().toEpochMilli();
  String serverZoneId = ZoneId.systemDefault().getId();
%>

</main>

<footer class="site-footer">
    <div class="container footer-row">
        <div class="footer-left">
            <span class="muted"></span>
            <span class="dot">•</span>
            <span id="footerServerClock" class="muted">Server time loading...</span>
        </div>

        <div class="footer-right muted">
            <span id="footerWeather">Weather: off</span>
            <button type="button" id="footerWeatherToggle" class="btn btn-ghost btn-sm">Enable</button>
        </div>
    </div>
</footer>

<div id="footerRuntimeData"
     style="display:none;"
     data-server-epoch-ms="<%= serverEpochMs %>"
     data-server-zone="<%= footerEsc(serverZoneId) %>"></div>

<script>
(() => {
  const dataEl = document.getElementById("footerRuntimeData");
  const clockEl = document.getElementById("footerServerClock");
  const weatherEl = document.getElementById("footerWeather");
  const weatherToggleEl = document.getElementById("footerWeatherToggle");
  if (!dataEl) return;

  const baseEpochMs = parseInt(dataEl.getAttribute("data-server-epoch-ms") || "0", 10);
  const serverZone = String(dataEl.getAttribute("data-server-zone") || "UTC").trim() || "UTC";
  const startedClientMs = Date.now();

  function formatServerDateTime(ms) {
    const now = new Date(ms);
    try {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "full",
        timeStyle: "long",
        timeZone: serverZone
      }).format(now);
    } catch (err) {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "full",
        timeStyle: "long"
      }).format(now) + " (" + serverZone + ")";
    }
  }

  function tickClock() {
    if (!clockEl) return;
    const ms = baseEpochMs + (Date.now() - startedClientMs);
    clockEl.textContent =   formatServerDateTime(ms)+" (" + serverZone + ")";
  }

  tickClock();
  setInterval(tickClock, 1000);

  if (!weatherEl || !weatherToggleEl) return;

  const cfg = document.getElementById("uiThemeConfig");
  const scope = cfg ? String(cfg.getAttribute("data-pref-scope") || "public") : "public";
  const WEATHER_ENABLED_KEY = "ui.footer.weather.enabled." + scope;

  function readWeatherEnabled() {
    try {
      return String(localStorage.getItem(WEATHER_ENABLED_KEY) || "").trim() === "1";
    } catch (ignored) {
      return false;
    }
  }

  function writeWeatherEnabled(enabled) {
    try {
      localStorage.setItem(WEATHER_ENABLED_KEY, enabled ? "1" : "0");
    } catch (ignored) {}
  }

  function updateWeatherToggle(enabled, isLoading) {
    if (!weatherToggleEl) return;
    weatherToggleEl.textContent = enabled ? "Disable" : "Enable";
    weatherToggleEl.disabled = !!isLoading;
    weatherToggleEl.title = enabled ? "Disable weather fetch" : "Enable weather fetch";
  }

  function weatherCodeLabel(code) {
    const c = Number(code);
    if (c === 0) return "Clear";
    if (c === 1) return "Mostly clear";
    if (c === 2) return "Partly cloudy";
    if (c === 3) return "Overcast";
    if (c === 45 || c === 48) return "Fog";
    if (c === 51 || c === 53 || c === 55) return "Drizzle";
    if (c === 56 || c === 57) return "Freezing drizzle";
    if (c === 61 || c === 63 || c === 65) return "Rain";
    if (c === 66 || c === 67) return "Freezing rain";
    if (c === 71 || c === 73 || c === 75 || c === 77) return "Snow";
    if (c === 80 || c === 81 || c === 82) return "Rain showers";
    if (c === 85 || c === 86) return "Snow showers";
    if (c === 95) return "Thunderstorm";
    if (c === 96 || c === 99) return "Thunderstorm with hail";
    return "Weather " + c;
  }

  function weatherError(message) {
    weatherEl.textContent = "Weather: " + message;
  }

  async function loadWeather() {
    if (!navigator.geolocation) {
      weatherError("location unavailable");
      return;
    }

    weatherEl.textContent = "Weather: locating...";
    updateWeatherToggle(true, true);

    navigator.geolocation.getCurrentPosition(async (position) => {
      const lat = position.coords.latitude;
      const lon = position.coords.longitude;

      const weatherUrl =
        "https://api.open-meteo.com/v1/forecast"
        + "?latitude=" + encodeURIComponent(String(lat))
        + "&longitude=" + encodeURIComponent(String(lon))
        + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
        + "&temperature_unit=fahrenheit"
        + "&wind_speed_unit=mph"
        + "&timezone=auto";

      const geoUrl =
        "https://geocoding-api.open-meteo.com/v1/reverse"
        + "?latitude=" + encodeURIComponent(String(lat))
        + "&longitude=" + encodeURIComponent(String(lon))
        + "&language=en&count=1";

      try {
        const weatherPromise = fetch(weatherUrl, { headers: { "Accept": "application/json" } });
        const geoPromise = fetch(geoUrl, { headers: { "Accept": "application/json" } });
        const responses = await Promise.all([weatherPromise, geoPromise]);

        if (!responses[0].ok) throw new Error("weather service unavailable");
        const forecast = await responses[0].json();
        const geo = responses[1].ok ? await responses[1].json() : null;

        const current = forecast && forecast.current ? forecast.current : null;
        if (!current) {
          weatherError("currently unavailable");
          return;
        }

        let place = "";
        try {
          const first = geo && geo.results && geo.results.length > 0 ? geo.results[0] : null;
          if (first) {
            const parts = [];
            if (first.name) parts.push(String(first.name));
            if (first.admin1) parts.push(String(first.admin1));
            place = parts.join(", ");
          }
        } catch (ignore) {}

        const temp = (typeof current.temperature_2m === "number") ? Math.round(current.temperature_2m) + "°F" : "";
        const feels = (typeof current.apparent_temperature === "number") ? Math.round(current.apparent_temperature) + "°F" : "";
        const wind = (typeof current.wind_speed_10m === "number") ? Math.round(current.wind_speed_10m) + " mph" : "";
        const summary = weatherCodeLabel(current.weather_code);

        const left = place ? ("Weather (" + place + "): ") : "Weather: ";
        const tokens = [];
        if (temp) tokens.push(temp);
        if (summary) tokens.push(summary);
        if (feels) tokens.push("Feels " + feels);
        if (wind) tokens.push("Wind " + wind);

        weatherEl.textContent = left + tokens.join(" | ");
      } catch (err) {
        weatherError("unavailable");
      } finally {
        updateWeatherToggle(true, false);
      }
    }, (error) => {
      if (!error) {
        weatherError("location unavailable");
      } else if (error.code === 1) {
        weatherError("permission denied");
      } else if (error.code === 2) {
        weatherError("location unavailable");
      } else if (error.code === 3) {
        weatherError("location timeout");
      } else {
        weatherError("unavailable");
      }
      updateWeatherToggle(true, false);
    }, {
      enableHighAccuracy: false,
      timeout: 10000,
      maximumAge: 600000
    });
  }

  function applyWeatherState(enabled) {
    writeWeatherEnabled(enabled);
    updateWeatherToggle(enabled, false);
    if (!enabled) {
      weatherEl.textContent = "Weather: off";
      return;
    }
    loadWeather();
  }

  weatherToggleEl.addEventListener("click", function () {
    const currentlyEnabled = readWeatherEnabled();
    applyWeatherState(!currentlyEnabled);
  });

  applyWeatherState(readWeatherEnabled());
})();
</script>

</body>
</html>
