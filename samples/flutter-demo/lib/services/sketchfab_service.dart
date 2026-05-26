import 'dart:convert';
import 'package:http/http.dart' as http;

/// A search result from the Sketchfab API.
class SketchfabModel {
  final String uid;
  final String name;
  final String? thumbnailUrl;
  final String? authorName;
  final int viewCount;
  final int likeCount;

  const SketchfabModel({
    required this.uid,
    required this.name,
    this.thumbnailUrl,
    this.authorName,
    this.viewCount = 0,
    this.likeCount = 0,
  });

  /// URL to download the GLB file (requires authentication for actual download).
  String get downloadUrl =>
      'https://api.sketchfab.com/v3/models/$uid/download';

  /// Web URL for viewing on Sketchfab.
  String get webUrl => 'https://sketchfab.com/3d-models/$uid';

  factory SketchfabModel.fromJson(Map<String, dynamic> json) {
    // Extract the best available thumbnail.
    String? thumb;
    final thumbnails = json['thumbnails'] as Map<String, dynamic>?;
    if (thumbnails != null) {
      final images = thumbnails['images'] as List<dynamic>?;
      if (images != null && images.isNotEmpty) {
        // Prefer a medium-sized thumbnail.
        final medium = images.firstWhere(
          (img) => (img['width'] as int?) == 200,
          orElse: () => images.first,
        );
        thumb = medium['url'] as String?;
      }
    }

    final user = json['user'] as Map<String, dynamic>?;

    return SketchfabModel(
      uid: json['uid'] as String,
      name: json['name'] as String? ?? 'Untitled',
      thumbnailUrl: thumb,
      authorName: user?['displayName'] as String? ?? user?['username'] as String?,
      viewCount: json['viewCount'] as int? ?? 0,
      likeCount: json['likeCount'] as int? ?? 0,
    );
  }
}

/// Service for searching Sketchfab's public API.
///
/// The search endpoint is public and does not require an API key.
/// Downloading models requires authentication (not implemented here).
class SketchfabService {
  static const _baseUrl = 'https://api.sketchfab.com/v3';

  /// `User-Agent` header sent on every Sketchfab request.
  ///
  /// AWS CloudFront's WAF fronting `api.sketchfab.com` returns HTTP 202 +
  /// empty body + `x-amzn-waf-action: challenge` to any request carrying
  /// Dart's default `User-Agent: Dart/<version> (dart:io)` — a known
  /// scraper signature. The empty body crashes `jsonDecode` with
  /// `FormatException: Unexpected end of input`, leaving Flutter demo's
  /// Sketchfab browse silently empty. Mirrors the Android #2191 fix.
  static const _userAgent =
      'SceneViewDemo/4.15.2 (Flutter; Android/iOS; +https://sceneview.github.io)';

  static const _defaultHeaders = <String, String>{
    'User-Agent': _userAgent,
    'Accept': 'application/json',
  };

  final http.Client _client;

  SketchfabService({http.Client? client}) : _client = client ?? http.Client();

  /// Search for downloadable 3D models matching [query].
  ///
  /// Returns up to [count] results (default 24). Supports [cursor]-based
  /// pagination for fetching subsequent pages.
  Future<SketchfabSearchResult> search(
    String query, {
    int count = 24,
    String? cursor,
  }) async {
    final params = <String, String>{
      'type': 'models',
      'downloadable': 'true',
      'q': query,
      'count': count.toString(),
    };
    if (cursor != null) {
      params['cursor'] = cursor;
    }

    final uri = Uri.parse('$_baseUrl/search').replace(queryParameters: params);
    final response = await _client.get(uri, headers: _defaultHeaders);

    if (response.statusCode != 200) {
      throw SketchfabException(
        'Search failed (${response.statusCode}): ${response.reasonPhrase}',
      );
    }

    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final results = (json['results'] as List<dynamic>?)
            ?.map((e) => SketchfabModel.fromJson(e as Map<String, dynamic>))
            .toList() ??
        [];

    return SketchfabSearchResult(
      models: results,
      nextCursor: json['cursors']?['next'] as String?,
      totalCount: json['totalCount'] as int? ?? results.length,
    );
  }

  void dispose() {
    _client.close();
  }
}

/// Result of a Sketchfab search including pagination info.
class SketchfabSearchResult {
  final List<SketchfabModel> models;
  final String? nextCursor;
  final int totalCount;

  const SketchfabSearchResult({
    required this.models,
    this.nextCursor,
    this.totalCount = 0,
  });

  bool get hasMore => nextCursor != null;
}

class SketchfabException implements Exception {
  final String message;
  const SketchfabException(this.message);

  @override
  String toString() => 'SketchfabException: $message';
}
