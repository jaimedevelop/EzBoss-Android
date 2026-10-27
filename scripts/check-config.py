#!/usr/bin/env python3
"""Check the built callback against BuildConfig without printing configuration values."""
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


def main():
    source = (ROOT / 'app/build/generated/source/buildConfig/debug/pro/ezboss/mobile/BuildConfig.java').read_text()
    strings = dict((key, json.loads(value)) for key, value in re.findall(r'public static final String (\w+) = ("(?:\\.|[^"\\])*");', source))
    public_auth = {key for key in strings if key.startswith('AUTH0_') or key.startswith('API_')}
    if public_auth != {'AUTH0_DOMAIN', 'AUTH0_CLIENT_ID', 'AUTH0_AUDIENCE', 'API_BASE_URL'}:
        raise ValueError('BuildConfig must expose only the four approved public identity/API settings')
    manifest = ET.parse(ROOT / 'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml').getroot()
    app = manifest.find('application')
    if app is None or app.get(ANDROID + 'usesCleartextTraffic') != 'false':
        raise ValueError('The built application must deny cleartext traffic')
    if app.get(ANDROID + "allowBackup") != "false":
        raise ValueError("Credential storage must be excluded from application backup")
    activities = [activity for activity in app.findall('activity') if activity.get(ANDROID + 'name') == 'com.auth0.android.provider.RedirectActivity']
    if len(activities) != 1 or activities[0].get(ANDROID + 'exported') != 'true':
        raise ValueError('Expected exactly one exported Auth0 SDK callback activity')
    filters = activities[0].findall('intent-filter')
    if len(filters) != 1 or filters[0].get(ANDROID + 'autoVerify') in ('true', '1'):
        raise ValueError('Custom-scheme callback must have one intent filter without HTTPS autoVerify')
    data = filters[0].findall('data')
    app_id = strings.get('APPLICATION_ID')
    if len(data) != 1 or not app_id:
        raise ValueError('Expected one callback data entry and an application identifier')
    expected = {ANDROID + 'scheme': app_id, ANDROID + 'host': strings['AUTH0_DOMAIN'],
                ANDROID + 'path': f'/android/{app_id}/callback'}
    if data[0].attrib != expected:
        raise ValueError('Callback must exactly match the build application ID and Auth0 domain; broad path matching is forbidden')
    if any(word in source.lower() for word in ['client_secret', 'clientsecret', 'management_secret', 'access_token', 'refresh_token']):
        raise ValueError('Server credential/token field found in BuildConfig')
    print('PASS: merged callback matches build identity, exact path, no autoVerify, public-only Auth0/API fields, no cleartext, backup disabled')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        # All ValueErrors raised above have static, value-free diagnostics.
        print('Configuration check failed; inspect callback/build field definitions locally.', file=sys.stderr)
        sys.exit(1)
    except (OSError, ET.ParseError, KeyError):
        print('Build debug first, then rerun scripts/check-config.py.', file=sys.stderr)
        sys.exit(1)
