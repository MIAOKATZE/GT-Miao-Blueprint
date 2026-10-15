"""Bounded read-only NBT parser for current fixture receipts; imports never execute tests."""
import gzip
import io
import struct


class Reader:
    def __init__(self, data, *, typed=False):
        self.stream = io.BytesIO(data)
        self.typed = typed

    def wrap(self, kind, value):
        return (kind, value) if self.typed else value

    def take(self, amount):
        if amount < 0 or amount > 32 * 1024 * 1024:
            raise ValueError('Invalid NBT field length')
        value = self.stream.read(amount)
        if len(value) != amount:
            raise ValueError('Truncated NBT receipt')
        return value

    def number(self, pattern):
        return struct.unpack('>' + pattern, self.take(struct.calcsize(pattern)))[0]

    def string(self):
        return self.take(self.number('H')).decode('utf-8')

    def payload(self, kind, depth=0):
        if depth > 64:
            raise ValueError('NBT nesting exceeds receipt limit')
        numbers = {1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}
        if kind in numbers:
            return self.wrap(kind, self.number(numbers[kind]))
        if kind == 7:
            return self.wrap(kind, list(self.take(self.number('i'))))
        if kind == 8:
            return self.wrap(kind, self.string())
        if kind == 9:
            item_kind, length = self.number('B'), self.number('i')
            if not 0 <= length <= 100000:
                raise ValueError('Invalid NBT list length')
            values = [self.payload(item_kind, depth + 1) for _ in range(length)]
            return self.wrap(kind, (item_kind, values) if self.typed else values)
        if kind == 10:
            value = {}
            while True:
                item_kind = self.number('B')
                if item_kind == 0:
                    return self.wrap(kind, value)
                key = self.string()
                if key in value:
                    raise ValueError('Duplicate receipt compound key: ' + key)
                value[key] = self.payload(item_kind, depth + 1)
        if kind in (11, 12):
            length = self.number('i')
            if not 0 <= length <= 100000:
                raise ValueError('Invalid NBT array length')
            return self.wrap(kind, [self.number('i' if kind == 11 else 'q') for _ in range(length)])
        raise ValueError('Unknown NBT tag type: ' + str(kind))


def read_nbt(path, *, typed=False):
    """Opt-in typed tuples preserve every tag ID, including empty list element types."""
    return read_nbt_bytes(path.read_bytes(), typed=typed)


def read_nbt_bytes(raw, *, typed=False):
    """Apply the same bounded parser to an encoded blueprint's native NBT bytes."""
    if len(raw) > 32 * 1024 * 1024:
        raise ValueError('Receipt exceeds NBT size limit')
    if raw[:2] == b'\x1f\x8b':
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            raw = stream.read(32 * 1024 * 1024 + 1)
        if len(raw) > 32 * 1024 * 1024:
            raise ValueError('Expanded receipt exceeds NBT size limit')
    reader = Reader(raw, typed=typed)
    if reader.number('B') != 10:
        raise ValueError('Expected compound NBT receipt root')
    reader.string()
    value = reader.payload(10)
    if reader.stream.read(1):
        raise ValueError('Trailing data in NBT receipt')
    return value
