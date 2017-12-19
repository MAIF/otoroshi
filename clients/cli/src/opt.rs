pub trait OptionExtension {
    type OptionInner;
    fn foreach<B, F: FnOnce(&Self::OptionInner) -> B>(self, F);
    fn flat_map<B, F: FnOnce(&Self::OptionInner) -> Option<B>>(self, F) -> Option<B>;
    fn filter<F: FnOnce(&Self::OptionInner) -> bool>(self, F) -> Self;
    fn filter_not<F: FnOnce(&Self::OptionInner) -> bool>(self, F) -> Self;
    fn exists<F: FnOnce(&Self::OptionInner) -> bool>(self, F) -> bool;
    fn is_defined(self) -> bool;
    fn is_empty(self) -> bool;
    fn non_empty(self) -> bool;
    fn get(self) -> Self::OptionInner;
    fn get_or_felse<F: FnOnce() -> Self::OptionInner>(self, F) -> Self::OptionInner;
    fn get_or_else(self, Self::OptionInner) -> Self::OptionInner;
    fn fold<B, F: FnOnce() -> B, FF: FnOnce(&Self::OptionInner) -> B>(self, F, FF) -> B;
}

impl<T> OptionExtension for Option<T> {
    type OptionInner = T;

    fn is_defined(self) -> bool {
        self.is_some()
    }

    fn is_empty(self) -> bool {
        self.is_none()
    }

    fn non_empty(self) -> bool {
        self.is_some()
    }

    fn get(self) -> T {
        self.unwrap()
    }

    fn foreach<B, F: FnOnce(&Self::OptionInner) -> B>(self, callback: F) {
        match self {
            None => (),
            Some(x) => {
                callback(&x);
                ()
            }
        }
    }

    fn fold<B, F: FnOnce() -> B, FF: FnOnce(&Self::OptionInner) -> B>(self, f: F, ff: FF) -> B {
        match self {
            None => f(),
            Some(x) => ff(&x),
        }
    }

    fn filter<F: FnOnce(&T) -> bool>(self, callback: F) -> Option<T> {
        match self {
            None => None,
            Some(x) => if callback(&x) {
                Some(x)
            } else {
                None
            },
        }
    }

    fn filter_not<F: FnOnce(&T) -> bool>(self, callback: F) -> Option<T> {
        match self {
            None => None,
            Some(x) => if callback(&x) {
                None
            } else {
                Some(x)
            },
        }
    }

    fn exists<F: FnOnce(&T) -> bool>(self, callback: F) -> bool {
        match self {
            None => false,
            Some(x) => if callback(&x) {
                true
            } else {
                false
            },
        }
    }

    fn get_or_felse<F: FnOnce() -> T>(self, callback: F) -> T {
        self.unwrap_or(callback())
    }

    fn get_or_else(self, callback: T) -> T {
        self.unwrap_or(callback)
    }

    fn flat_map<B, F: FnOnce(&Self::OptionInner) -> Option<B>>(self, callback: F) -> Option<B> {
        match self {
            None => None,
            Some(x) => callback(&x),
        }
    }
}
