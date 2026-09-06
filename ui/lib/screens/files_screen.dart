import 'package:flutter/material.dart';

import '../l10n/strings.dart';
import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';

/// The instance's own files, browsed the way a file manager browses a phone.
///
/// ## Why this screen exists
///
/// A virtual app's directories are inside UNIQUE's private storage, so no file manager on
/// the device can open them. That alone would be reason enough — a real device lets you
/// look at your own files — but there is a harder one.
///
/// Since Android 11 the platform hides `Android/data` and `Android/obb` from every app,
/// **including one holding All-files access**. UNIQUE cannot copy a game's expansion
/// files in, however many permissions the user grants; asking for that permission was
/// wrong and the screen that asked has been removed. What works is the user handing the
/// file over from wherever they have it, and that is the **Import** button here.
///
/// The paths shown are the guest's own — `/sdcard/Android/obb/com.example.game` — because
/// those are the paths a game's own instructions name.
class FilesScreen extends StatefulWidget {
  const FilesScreen({super.key, required this.app, required this.state});

  final VirtualApp app;
  final AppState state;

  @override
  State<FilesScreen> createState() => _FilesScreenState();
}

class _FilesScreenState extends State<FilesScreen> {
  /// Where we are. Empty is the root list, which is where the screen opens: a guest has
  /// two trees and neither contains the other, so there is no one directory above them.
  String _path = '';
  GuestListing? _listing;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load('');
  }

  Future<void> _load(String path) async {
    setState(() => _busy = true);
    final listing = await widget.state.listFiles(widget.app.vuid, path);
    if (!mounted) return;
    setState(() {
      _path = path;
      _listing = listing;
      _busy = false;
    });
  }

  /// The directory above [_path], or empty when it is a root.
  String get _parent {
    final roots = _listing?.roots ?? const <GuestRoot>[];
    for (final root in roots) {
      if (_path == root.path) return '';
    }
    final cut = _path.lastIndexOf('/');
    return cut <= 0 ? '' : _path.substring(0, cut);
  }

  Future<void> _import() async {
    final s = Strings.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    final result = await widget.state.importFilesInto(widget.app.vuid, _path);
    if (!mounted) return;
    setState(() => _busy = false);
    await _load(_path);
    if (!mounted) return;
    messenger.showSnackBar(SnackBar(
      content: Text(result.ok
          ? (result.files == 0
              ? s.t('files.importedNothing')
              : s.t('files.imported', {
                  'files': '${result.files}',
                  'size': formatBytes(result.bytes),
                }))
          : result.describe(s, s.t('common.failed'))),
    ));
  }

  Future<void> _newFolder() async {
    final s = Strings.of(context);
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(s.t('files.newFolder')),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(hintText: s.t('files.folderName')),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(s.t('common.cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: Text(s.t('common.create')),
          ),
        ],
      ),
    );
    if (name == null || name.isEmpty || !mounted) return;
    await widget.state.createFolder(widget.app.vuid, _path, name);
    if (!mounted) return;
    await _load(_path);
  }

  Future<void> _delete(GuestFile entry) async {
    final s = Strings.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(s.t('files.deleteTitle')),
        content: Text(s.t('files.deleteBody', {'name': entry.name})),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(s.t('common.cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(s.t('common.delete')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await widget.state.deleteFile(widget.app.vuid, entry.path);
    if (!mounted) return;
    await _load(_path);
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final listing = _listing;
    final atRoot = _path.isEmpty;

    return Scaffold(
      appBar: AppBar(
        title: Text(s.t('files.title')),
        // The path, not the app name: the app name is on the screen before this one and
        // the path is the thing that changes as you move.
        bottom: atRoot
            ? null
            : PreferredSize(
                preferredSize: const Size.fromHeight(28),
                child: Padding(
                  padding: const EdgeInsets.only(
                    left: UniqueSpace.lg,
                    right: UniqueSpace.lg,
                    bottom: UniqueSpace.sm,
                  ),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      _path,
                      style: Theme.of(context).textTheme.bodySmall,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ),
              ),
        leading: atRoot
            ? null
            : IconButton(
                icon: const Icon(Icons.arrow_back_rounded),
                onPressed: () => _load(_parent),
              ),
        actions: [
          if (!atRoot)
            IconButton(
              icon: const Icon(Icons.create_new_folder_outlined),
              tooltip: s.t('files.newFolder'),
              onPressed: _busy ? null : _newFolder,
            ),
        ],
      ),
      floatingActionButton: atRoot
          ? null
          : FloatingActionButton.extended(
              onPressed: _busy ? null : _import,
              icon: const Icon(Icons.add_rounded),
              label: Text(s.t('files.import')),
            ),
      body: listing == null
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: () => _load(_path),
              child: atRoot
                  ? _roots(context, listing)
                  : _entries(context, listing, s),
            ),
    );
  }

  Widget _roots(BuildContext context, GuestListing listing) {
    final s = Strings.of(context);
    return ListView(
      padding: const EdgeInsets.all(UniqueSpace.lg),
      children: [
        // Said once, here, instead of on every app's details screen: the OBB directory is
        // the reason most people open this, and knowing where it is beats being warned.
        NoticeBanner(
          tone: NoticeTone.info,
          title: s.t('files.aboutTitle'),
          message: s.t('files.aboutBody', {'package': widget.app.packageName}),
        ),
        const SizedBox(height: UniqueSpace.md),
        for (final root in listing.roots)
          Card(
            child: ListTile(
              leading: const Icon(Icons.folder_rounded),
              title: Text(root.label),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _load(root.path),
            ),
          ),
      ],
    );
  }

  Widget _entries(BuildContext context, GuestListing listing, Strings s) {
    if (listing.entries.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(UniqueSpace.lg),
        children: [
          NoticeBanner(
            tone: NoticeTone.info,
            title: s.t('files.emptyTitle'),
            message: s.t('files.emptyBody'),
          ),
        ],
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.only(bottom: 96),
      itemCount: listing.entries.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final entry = listing.entries[index];
        return ListTile(
          leading: Icon(
            entry.isDirectory ? Icons.folder_rounded : Icons.insert_drive_file_rounded,
            color: entry.isDirectory ? UniqueColors.accent : null,
          ),
          title: Text(entry.name, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            entry.isDirectory
                ? (entry.children < 0
                    ? s.t('files.unreadable')
                    : s.t('files.items', {'count': '${entry.children}'}))
                : formatBytes(entry.bytes),
          ),
          trailing: IconButton(
            icon: const Icon(Icons.delete_outline_rounded),
            tooltip: s.t('common.delete'),
            onPressed: () => _delete(entry),
          ),
          onTap: entry.isDirectory ? () => _load(entry.path) : null,
        );
      },
    );
  }
}
