class DownloadItem {
  final String id;
  final String url;
  final String title;
  final String thumbnail;
  final String uploader;
  final bool audio;
  final int height; // 0 = best available
  final DateTime date;
  final String uri; // content uri of the saved file ('' for older entries)

  const DownloadItem({
    required this.id,
    required this.url,
    required this.title,
    required this.thumbnail,
    required this.uploader,
    required this.audio,
    required this.height,
    required this.date,
    this.uri = '',
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'url': url,
        'title': title,
        'thumbnail': thumbnail,
        'uploader': uploader,
        'audio': audio,
        'height': height,
        'date': date.millisecondsSinceEpoch,
        'uri': uri,
      };

  factory DownloadItem.fromJson(Map<String, dynamic> json) => DownloadItem(
        id: json['id'] as String,
        url: (json['url'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        thumbnail: (json['thumbnail'] as String?) ?? '',
        uploader: (json['uploader'] as String?) ?? '',
        audio: json['audio'] == true,
        height: (json['height'] as num?)?.toInt() ?? 0,
        date: DateTime.fromMillisecondsSinceEpoch((json['date'] as num).toInt()),
        uri: (json['uri'] as String?) ?? '',
      );
}
