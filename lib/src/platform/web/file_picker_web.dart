import 'dart:async';
import 'dart:js_interop';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:file_picker/src/api/file_picker_types.dart';
import 'package:file_picker/src/api/file_picker_result.dart';
import 'package:file_picker/src/api/platform_file.dart';
import 'package:file_picker/src/api/android_saf_options.dart';
import 'package:file_picker/src/platform/file_picker_platform_interface.dart';
import 'package:flutter_web_plugins/flutter_web_plugins.dart';
import 'package:path/path.dart' as p;
import 'package:web/web.dart';

class FilePickerWeb extends FilePickerPlatform {
  late Element _target;
  final String _kFilePickerInputsDomId = '__file_picker_web-file-input';
  final double _kDialogAnchorInset = 8;
  final double _kDialogAnchorSize = 1;

  final int _readStreamChunkSize = 1000 * 1000; // 1 MB

  Element? _lastInteractionTarget;
  double? _lastInteractionClientX;
  double? _lastInteractionClientY;

  FilePickerWeb._() {
    _target = _ensureInitialized(_kFilePickerInputsDomId);
    _registerAnchorTracking();
  }

  static void registerWith(Registrar registrar) {
    FilePickerPlatform.instance = FilePickerWeb._();
  }

  /// Initializes a DOM container where we can host input elements.
  Element _ensureInitialized(String id) {
    Element? target = document.querySelector('#$id');
    if (target == null) {
      final Element targetElement = document.createElement(
        'flt-file-picker-inputs',
      )..id = id;

      document.querySelector('body')!.children.add(targetElement);
      target = targetElement;
    }
    return target;
  }

  void _registerAnchorTracking() {
    document.addEventListener('focusin', _cacheFocusedElement.toJS);
    document.addEventListener('pointerdown', _cachePointerAnchor.toJS);
    document.addEventListener('mousedown', _cacheMouseAnchor.toJS);
    document.addEventListener('touchstart', _cacheTouchAnchor.toJS);
  }

  void _cacheFocusedElement(Event event) {
    _cacheAnchorTarget(event.target);
  }

  void _cachePointerAnchor(Event event) {
    final PointerEvent pointerEvent = event as PointerEvent;
    _cacheAnchorTarget(pointerEvent.target);
    _lastInteractionClientX = pointerEvent.clientX.toDouble();
    _lastInteractionClientY = pointerEvent.clientY.toDouble();
  }

  void _cacheMouseAnchor(Event event) {
    final MouseEvent mouseEvent = event as MouseEvent;
    _cacheAnchorTarget(mouseEvent.target);
    _lastInteractionClientX = mouseEvent.clientX.toDouble();
    _lastInteractionClientY = mouseEvent.clientY.toDouble();
  }

  void _cacheTouchAnchor(Event event) {
    final TouchEvent touchEvent = event as TouchEvent;
    _cacheAnchorTarget(touchEvent.target);

    final TouchList touches = touchEvent.changedTouches.length > 0
        ? touchEvent.changedTouches
        : touchEvent.touches;
    final Touch? touch = touches.item(0);
    if (touch == null) {
      return;
    }

    _lastInteractionClientX = touch.clientX;
    _lastInteractionClientY = touch.clientY;
  }

  void _cacheAnchorTarget(EventTarget? target) {
    if (target == null || !target.isA<Element>()) {
      return;
    }

    final Element element = target as Element;
    if (element == _target || element.id == _kFilePickerInputsDomId) {
      return;
    }

    _lastInteractionTarget = element;
  }

  void _clearTargetChildren() {
    Node? firstChild = _target.firstChild;
    while (firstChild != null) {
      _target.removeChild(firstChild);
      firstChild = _target.firstChild;
    }
  }

  void _positionUploadInput(HTMLInputElement uploadInput) {
    final Element? anchorElement = _resolveAnchorElement();
    final DOMRect? anchorRect = anchorElement?.getBoundingClientRect();

    double left = _lastInteractionClientX ?? 0;
    double top = _lastInteractionClientY ?? 0;

    if (anchorRect != null) {
      final double anchorWidth = math.max(anchorRect.width, 0);
      final double anchorHeight = math.max(anchorRect.height, 0);

      left = anchorRect.left + math.min(_kDialogAnchorInset, anchorWidth / 2);
      top = anchorRect.top + math.min(_kDialogAnchorInset, anchorHeight / 2);
    }

    final double maxLeft = math.max(
      window.innerWidth.toDouble() - _kDialogAnchorSize,
      0,
    );
    final double maxTop = math.max(
      window.innerHeight.toDouble() - _kDialogAnchorSize,
      0,
    );

    left = left.clamp(0, maxLeft).toDouble();
    top = top.clamp(0, maxTop).toDouble();

    uploadInput.style
      ..position = 'fixed'
      ..left = '${left}px'
      ..top = '${top}px'
      ..width = '${_kDialogAnchorSize}px'
      ..height = '${_kDialogAnchorSize}px'
      ..margin = '0'
      ..padding = '0'
      ..border = '0'
      ..opacity = '0.0001'
      ..overflow = 'hidden'
      ..zIndex = '2147483647';
  }

  Element? _resolveAnchorElement() {
    final Element? activeElement = document.activeElement;
    if (_isUsableAnchorElement(activeElement)) {
      return activeElement;
    }

    if (_isUsableAnchorElement(_lastInteractionTarget)) {
      return _lastInteractionTarget;
    }

    return null;
  }

  bool _isUsableAnchorElement(Element? element) {
    if (element == null ||
        element == _target ||
        element.id == _kFilePickerInputsDomId) {
      return false;
    }

    final String tagName = element.tagName.toLowerCase();
    if (tagName == 'body' || tagName == 'html') {
      return false;
    }

    final DOMRect rect = element.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      return false;
    }

    final double viewportWidth = window.innerWidth.toDouble();
    final double viewportHeight = window.innerHeight.toDouble();
    final bool coversMostOfViewport =
        rect.width >= viewportWidth * 0.9 &&
        rect.height >= viewportHeight * 0.9;

    return !coversMostOfViewport;
  }

  void _showUploadPicker(HTMLInputElement uploadInput) {
    try {
      uploadInput.showPicker();
      return;
    } catch (_) {
      uploadInput.click();
    }
  }

  @override
  Future<String?> getDirectoryPath({
    String? dialogTitle,
    bool lockParentWindow = false,
    String? initialDirectory,
    AndroidSAFOptions? androidSafOptions,
  }) async {
    throw UnimplementedError('getDirectoryPath() has not been implemented.');
  }

  @override
  Future<FilePickerResult?> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    bool allowMultiple = false,
    Function(FilePickerStatus)? onFileLoading,
    bool withData = true,
    bool withReadStream = false,
    bool lockParentWindow = false,
    bool readSequential = false,
    bool cancelUploadOnWindowBlur = true,
    int compressionQuality = 0,
    AndroidSAFOptions? androidSafOptions,
  }) async {
    if (type != FileType.custom && (allowedExtensions?.isNotEmpty ?? false)) {
      throw Exception(
        'You are setting a type [$type]. Custom extension filters are only allowed with FileType.custom, please change it or remove filters.',
      );
    }

    Completer<List<PlatformFile>?>? filesCompleter =
        Completer<List<PlatformFile>?>();

    String accept = _fileType(type, allowedExtensions);
    HTMLInputElement uploadInput = HTMLInputElement();
    uploadInput.type = 'file';
    uploadInput.draggable = true;
    uploadInput.multiple = allowMultiple;
    uploadInput.accept = accept;

    bool changeEventTriggered = false;

    if (onFileLoading != null) {
      onFileLoading(FilePickerStatus.picking);
    }

    void changeEventListener(Event e) async {
      if (changeEventTriggered) {
        return;
      }
      changeEventTriggered = true;

      final FileList files = uploadInput.files!;
      final List<PlatformFile> pickedFiles = [];

      void addPickedFile(
        File file,
        Uint8List? bytes,
        String? path,
        Stream<List<int>>? readStream,
      ) {
        String? blobUrl = path;

        // If no explicit path was provided and no bytes were loaded into
        // memory, create a fetchable Blob URL from the original File so
        // callers can later fetch or stream the data.
        if ((blobUrl == null || blobUrl.isEmpty) && (bytes == null)) {
          try {
            blobUrl = URL.createObjectURL(file);
          } catch (_) {
            blobUrl = null;
          }
        } else if (bytes != null && bytes.isNotEmpty) {
          final blob = Blob(
            [bytes.toJS].toJS,
            BlobPropertyBag(type: file.type),
          );

          blobUrl = URL.createObjectURL(blob);
        }
        pickedFiles.add(
          PlatformFile(
            name: file.name,
            path: blobUrl,
            size: bytes != null ? bytes.length : file.size,
            bytes: bytes,
            readStream: readStream,
          ),
        );

        if (pickedFiles.length >= files.length) {
          if (onFileLoading != null) {
            onFileLoading(FilePickerStatus.done);
          }
          filesCompleter?.complete(pickedFiles);
        }
      }

      for (int i = 0; i < files.length; i++) {
        final File? file = files.item(i);
        if (file == null) {
          continue;
        }

        if (withReadStream) {
          addPickedFile(file, null, null, _openFileReadStream(file));
          continue;
        }

        if (!withData) {
          addPickedFile(file, null, null, null);
          continue;
        }

        final syncCompleter = Completer<void>();
        final FileReader reader = FileReader();
        reader.onLoadEnd.listen((e) {
          ByteBuffer? byteBuffer = (reader.result as JSArrayBuffer?)?.toDart;
          addPickedFile(file, byteBuffer?.asUint8List(), null, null);
          syncCompleter.complete();
        });
        reader.readAsArrayBuffer(file);
        if (readSequential) {
          await syncCompleter.future;
        }
      }
    }

    void cancelledEventListener(Event _) {
      window.removeEventListener('focus', cancelledEventListener.toJS);

      // This listener is called before the input changed event,
      // and the `uploadInput.files` value is still null
      // Wait for results from js to dart
      Future.delayed(Duration(seconds: 1)).then((value) {
        if (!changeEventTriggered) {
          changeEventTriggered = true;
          filesCompleter?.complete(null);
        }
      });
    }

    uploadInput.onChange.listen(changeEventListener);
    uploadInput.addEventListener('change', changeEventListener.toJS);
    uploadInput.addEventListener('cancel', cancelledEventListener.toJS);

    if (cancelUploadOnWindowBlur) {
      // Listen focus event for cancelled
      window.addEventListener('focus', cancelledEventListener.toJS);
    }

    // Keep the input in the DOM until the native picker closes so browsers
    // like Safari on iOS can anchor the dialog to the tapped control.
    _clearTargetChildren();
    _positionUploadInput(uploadInput);
    _target.children.add(uploadInput);
    final Future<List<PlatformFile>?> pendingFiles = filesCompleter.future
        .whenComplete(_clearTargetChildren);

    try {
      _showUploadPicker(uploadInput);
    } catch (_) {
      _clearTargetChildren();
      rethrow;
    }

    final List<PlatformFile>? files = await pendingFiles;
    filesCompleter = null;

    return files == null ? null : FilePickerResult(files);
  }

  @override
  Future<String?> saveFile({
    String? dialogTitle,
    required String fileName,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    required Uint8List bytes,
    Function(FilePickerStatus)? onFileLoading,
    bool lockParentWindow = false,
  }) async {
    if (bytes.isEmpty) {
      throw ArgumentError(
        'The bytes are required when saving a file on the web.',
      );
    }

    if (fileName.isEmpty) {
      throw ArgumentError(
        'A file name is required when saving a file on the web.',
      );
    }

    if (p.extension(fileName).isEmpty) {
      throw ArgumentError(
        'The file name should include a valid file extension.',
      );
    }

    final blob = Blob([bytes.toJS].toJS);
    final url = URL.createObjectURL(blob);

    // Start a download by using a click event on an anchor element that contains the Blob.
    HTMLAnchorElement()
      ..href = url
      // Always open the file in a new tab.
      ..target = 'blank'
      ..download = fileName
      ..click();

    // Release the Blob URL after the download started.
    URL.revokeObjectURL(url);
    return null;
  }

  static String _fileType(FileType type, List<String>? allowedExtensions) {
    switch (type) {
      case FileType.any:
        return '';

      case FileType.audio:
        return 'audio/*';

      case FileType.image:
        return 'image/*';

      case FileType.video:
        return 'video/*';

      case FileType.media:
        return 'video/*|image/*';

      case FileType.custom:
        return allowedExtensions!.fold(
          '',
          (prev, next) => '${prev.isEmpty ? '' : '$prev,'} .$next',
        );
    }
  }

  Stream<List<int>> _openFileReadStream(File file) async* {
    final reader = FileReader();

    int start = 0;
    while (start < file.size) {
      final end = start + _readStreamChunkSize > file.size
          ? file.size
          : start + _readStreamChunkSize;
      final blob = file.slice(start, end);
      reader.readAsArrayBuffer(blob);
      await EventStreamProviders.loadEvent.forTarget(reader).first;
      final JSAny? readerResult = reader.result;
      if (readerResult == null) {
        continue;
      }

      // Handle the ArrayBuffer type. This maps to a `ByteBuffer` in Dart.
      if (readerResult.isA<JSArrayBuffer>()) {
        yield (readerResult as JSArrayBuffer).toDart.asUint8List();
        start += _readStreamChunkSize;
        continue;
      }

      if (readerResult.isA<JSArray>()) {
        // Assume this is a List<int>.
        yield (readerResult as JSArray).toDart.cast<int>();
        start += _readStreamChunkSize;
      }
    }
  }
}
