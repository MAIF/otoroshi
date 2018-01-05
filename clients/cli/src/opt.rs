pub trait OptionExtension {
    type OptionInner;
    fn m_map<B, F: FnOnce(&Self::OptionInner) -> B>(self, F) -> Option<B>;
    fn m_foreach<B, F: FnOnce(&Self::OptionInner) -> B>(self, F);
    fn m_flat_map<B, F: FnOnce(&Self::OptionInner) -> Option<B>>(self, F) -> Option<B>;
    fn m_filter<F: FnOnce(&Self::OptionInner) -> bool>(self, F) -> Self;
    fn m_filter_not<F: FnOnce(&Self::OptionInner) -> bool>(self, F) -> Self;
    fn m_exists<F: FnOnce(&Self::OptionInner) -> bool>(self, F) -> bool;
    fn m_is_defined(self) -> bool;
    fn m_is_empty(self) -> bool;
    fn m_non_empty(self) -> bool;
    fn m_get(self) -> Self::OptionInner;
    fn m_get_or_felse<F: FnOnce() -> Self::OptionInner>(self, F) -> Self::OptionInner;
    fn m_get_or_else(self, Self::OptionInner) -> Self::OptionInner;
    fn m_fold<B, F: FnOnce() -> B, FF: FnOnce(&Self::OptionInner) -> B>(self, F, FF) -> B;
}

impl<T> OptionExtension for Option<T> {
    type OptionInner = T;

    fn m_is_defined(self) -> bool {
        self.is_some()
    }

    fn m_is_empty(self) -> bool {
        self.is_none()
    }

    fn m_non_empty(self) -> bool {
        self.is_some()
    }

    fn m_get(self) -> T {
        self.unwrap()
    }

    fn m_foreach<B, F: FnOnce(&Self::OptionInner) -> B>(self, callback: F) {
        match self {
            None => (),
            Some(x) => {
                callback(&x);
                ()
            }
        }
    }

    fn m_map<B, F: FnOnce(&Self::OptionInner) -> B>(self, callback: F) -> Option<B> {
        match self {
            None => None,
            Some(x) => Some(callback(&x)),
        }
    }

    fn m_fold<B, F: FnOnce() -> B, FF: FnOnce(&Self::OptionInner) -> B>(self, f: F, ff: FF) -> B {
        match self {
            None => f(),
            Some(x) => ff(&x),
        }
    }

    fn m_filter<F: FnOnce(&T) -> bool>(self, callback: F) -> Option<T> {
        match self {
            None => None,
            Some(x) => if callback(&x) {
                Some(x)
            } else {
                None
            },
        }
    }

    fn m_filter_not<F: FnOnce(&T) -> bool>(self, callback: F) -> Option<T> {
        match self {
            None => None,
            Some(x) => if callback(&x) {
                None
            } else {
                Some(x)
            },
        }
    }

    fn m_exists<F: FnOnce(&T) -> bool>(self, callback: F) -> bool {
        match self {
            None => false,
            Some(x) => if callback(&x) {
                true
            } else {
                false
            },
        }
    }

    fn m_get_or_felse<F: FnOnce() -> T>(self, callback: F) -> T {
        self.unwrap_or(callback())
    }

    fn m_get_or_else(self, callback: T) -> T {
        self.unwrap_or(callback)
    }

    fn m_flat_map<B, F: FnOnce(&Self::OptionInner) -> Option<B>>(self, callback: F) -> Option<B> {
        match self {
            None => None,
            Some(x) => callback(&x),
        }
    }
}
